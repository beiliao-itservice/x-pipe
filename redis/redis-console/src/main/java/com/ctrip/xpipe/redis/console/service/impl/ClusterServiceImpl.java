package com.ctrip.xpipe.redis.console.service.impl;

import com.ctrip.xpipe.exception.XpipeRuntimeException;
import com.ctrip.xpipe.redis.console.annotation.DalTransaction;
import com.ctrip.xpipe.redis.console.constant.XPipeConsoleConstant;
import com.ctrip.xpipe.redis.console.dao.ClusterDao;
import com.ctrip.xpipe.redis.console.exception.BadRequestException;
import com.ctrip.xpipe.redis.console.migration.status.ClusterStatus;
import com.ctrip.xpipe.redis.console.model.*;
import com.ctrip.xpipe.redis.console.notifier.ClusterMetaModifiedNotifier;
import com.ctrip.xpipe.redis.console.query.DalQuery;
import com.ctrip.xpipe.redis.console.service.*;
import com.ctrip.xpipe.redis.console.util.DataModifiedTimeGenerator;
import com.ctrip.xpipe.utils.StringUtil;
import com.ctrip.xpipe.utils.VisibleForTesting;
import com.google.common.collect.Maps;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.unidal.dal.jdbc.DalException;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ClusterServiceImpl extends AbstractConsoleService<ClusterTblDao> implements ClusterService {

	@Autowired
	private DcService dcService;
	@Autowired
	private ClusterDao clusterDao;
	@Autowired
	private ClusterMetaModifiedNotifier notifier;
	@Autowired
	private ShardService shardService;
	@Autowired
	private OrganizationService organizationService;
	@Autowired
	private DcClusterShardService dcClusterShardService;

	private Random random = new Random();

	@Autowired
	private SentinelService sentinelService;
	
	@Override
	public ClusterTbl find(final String clusterName) {
		return queryHandler.handleQuery(new DalQuery<ClusterTbl>() {
			@Override
			public ClusterTbl doQuery() throws DalException {
				return dao.findClusterByClusterName(clusterName, ClusterTblEntity.READSET_FULL);
			}
    		
    	});
	}

	@Override
	public ClusterStatus clusterStatus(String clusterName) {

		ClusterTbl clusterTbl = find(clusterName);
		if(clusterTbl == null){
			throw new IllegalArgumentException("cluster not found:" + clusterName);
		}
		return ClusterStatus.valueOf(clusterTbl.getStatus());
	}

	@Override
	public ClusterTbl find(final long clusterId) {
		return queryHandler.handleQuery(new DalQuery<ClusterTbl>() {
			@Override
			public ClusterTbl doQuery() throws DalException {
				return dao.findByPK(clusterId, ClusterTblEntity.READSET_FULL);
			}
    		
    	});
	}


	@Override
	public List<String> findAllClusterNames() {

		List<ClusterTbl> clusterTbls = queryHandler.handleQuery(new DalQuery<List<ClusterTbl>>() {
			@Override
			public List<ClusterTbl> doQuery() throws DalException {
				return dao.findAllClusters(ClusterTblEntity.READSET_NAME);
			}
		});

		List<String> clusterNames = new ArrayList<>(clusterTbls.size());

		clusterTbls.forEach( clusterTbl -> clusterNames.add(clusterTbl.getClusterName()));

		return clusterNames;
	}

	@Override
	public Long getAllCount() {
		return queryHandler.handleQuery(new DalQuery<Long>() {
			@Override
			public Long doQuery() throws DalException {
				return dao.totalCount(ClusterTblEntity.READSET_COUNT).getCount();
			}
    	});
	}

	@Override
	@DalTransaction
	public ClusterTbl createCluster(ClusterModel clusterModel) {
		ClusterTbl cluster = clusterModel.getClusterTbl();
    	List<DcTbl> slaveDcs = clusterModel.getSlaveDcs();
    	List<ShardModel> shards = clusterModel.getShards();

    	// ensure active dc assigned
    	if(XPipeConsoleConstant.NO_ACTIVE_DC_TAG == cluster.getActivedcId()) {
    		throw new BadRequestException("No active dc assigned.");
    	}
    	ClusterTbl proto = dao.createLocal();
    	proto.setClusterName(cluster.getClusterName());
    	proto.setActivedcId(cluster.getActivedcId());
    	proto.setClusterDescription(cluster.getClusterDescription());
    	proto.setStatus(ClusterStatus.Normal.toString());
			proto.setIsXpipeInterested(true);
    	proto.setClusterLastModifiedTime(DataModifiedTimeGenerator.generateModifiedTime());
		if(!checkEmails(cluster.getClusterAdminEmails())) {
			throw new IllegalArgumentException("Emails should be ctrip emails and separated by comma or semicolon");
		}
    	proto.setClusterAdminEmails(cluster.getClusterAdminEmails());
		proto.setClusterOrgId(getOrgIdFromClusterOrgName(cluster));

    	final ClusterTbl queryProto = proto;
    	ClusterTbl result =  queryHandler.handleQuery(new DalQuery<ClusterTbl>(){
			@Override
			public ClusterTbl doQuery() throws DalException {
				return clusterDao.createCluster(queryProto);
			}
    	});

    	if(slaveDcs != null){
			for(DcTbl dc : slaveDcs) {
				bindDc(cluster.getClusterName(), dc.getDcName());
			}
		}

    	if(shards != null){
			for (ShardModel shard : shards) {
				shardService.createShard(cluster.getClusterName(), shard.getShardTbl(), shard.getSentinels());
			}
		}

    	return result;
	}

	public long getOrgIdFromClusterOrgName(ClusterTbl cluster) {
		String orgName = cluster.getClusterOrgName();
		OrganizationTbl organizationTbl = organizationService.getOrgByName(orgName);
		if(organizationTbl == null)
			return 0L;
		Long id = organizationTbl.getId();
		return id == null ? 0L : id;
	}

	@Override
	public ClusterTbl findClusterAndOrg(String clusterName) {
		ClusterTbl clusterTbl = clusterDao.findClusterAndOrgByName(clusterName);
		OrganizationTbl organizationTbl = clusterTbl.getOrganizationInfo();
		if(organizationTbl != null) {
			clusterTbl.setClusterOrgName(organizationTbl.getOrgName());
		}
		// Set null if no organization bind with cluster
		if(organizationTbl == null || organizationTbl.getId() == null) {
			clusterTbl.setOrganizationInfo(null);
		}
		return clusterTbl;
	}

	@Override
	public List<ClusterTbl> findClustersWithOrgInfoByActiveDcId(long activeDc) {
		List<ClusterTbl> result = clusterDao.findClustersWithOrgInfoByActiveDcId(activeDc);
		result = fillClusterOrgName(result);
		return setOrgNullIfNoOrgIdExsits(result);
	}

	@Override public List<ClusterTbl> findAllClustersWithOrgInfo() {
		List<ClusterTbl> result = clusterDao.findAllClusterWithOrgInfo();
		result = fillClusterOrgName(result);
		return setOrgNullIfNoOrgIdExsits(result);
	}

	private List<ClusterTbl> fillClusterOrgName(List<ClusterTbl> clusterTblList) {
		for(ClusterTbl cluster : clusterTblList) {
			cluster.setClusterOrgName(cluster.getOrganizationInfo().getOrgName());
		}
		return clusterTblList;
	}

	private List<ClusterTbl> setOrgNullIfNoOrgIdExsits(List<ClusterTbl> clusterTblList) {
		for(ClusterTbl cluster : clusterTblList) {
			OrganizationTbl organizationTbl = cluster.getOrganizationInfo();
			if(organizationTbl.getId() == null) {
				cluster.setOrganizationInfo(null);
			}
		}
		return clusterTblList;
	}

	@Override
	public void updateCluster(String clusterName, ClusterTbl cluster) {
		ClusterTbl proto = find(clusterName);
    	if(null == proto) throw new BadRequestException("Cannot find cluster");

		if(proto.getId() != cluster.getId()) {
			throw new BadRequestException("Cluster not match.");
		}
		proto.setClusterDescription(cluster.getClusterDescription());
		proto.setClusterLastModifiedTime(DataModifiedTimeGenerator.generateModifiedTime());
		if(!checkEmails(cluster.getClusterAdminEmails())) {
			throw new IllegalArgumentException("Emails should be ctrip emails and separated by comma or semicolon");
		}
		proto.setClusterAdminEmails(cluster.getClusterAdminEmails());
		proto.setClusterOrgId(getOrgIdFromClusterOrgName(cluster));
		// organization info should not be updated by cluster,
		// it's automatically updated by scheduled task
		proto.setOrganizationInfo(null);
		
		final ClusterTbl queryProto = proto;
    	queryHandler.handleQuery(new DalQuery<Integer>() {
			@Override
			public Integer doQuery() throws DalException {
				return clusterDao.updateCluster(queryProto);
			}
    	});
	}

	@Override
	public void updateActivedcId(long id, long activeDcId) {

		ClusterTbl clusterTbl = new ClusterTbl();
		clusterTbl.setId(id);
		clusterTbl.setActivedcId(activeDcId);

		queryHandler.handleQuery(new DalQuery<Integer>() {
			@Override
			public Integer doQuery() throws DalException {
				return dao.updateActivedcId(clusterTbl, ClusterTblEntity.UPDATESET_FULL);
			}
		});
	}

	@Override
	public void updateStatusById(long id, ClusterStatus clusterStatus) {

		ClusterTbl clusterTbl = new ClusterTbl();
		clusterTbl.setId(id);
		clusterTbl.setStatus(clusterStatus.toString());

		queryHandler.handleQuery(new DalQuery<Integer>() {
			@Override
			public Integer doQuery() throws DalException {
				return dao.updateStatusById(clusterTbl, ClusterTblEntity.UPDATESET_FULL);
			}
		});
	}

	@Override
	public void deleteCluster(String clusterName) {
		ClusterTbl proto = find(clusterName);
    	if(null == proto) throw new BadRequestException("Cannot find cluster");
    	proto.setClusterLastModifiedTime(DataModifiedTimeGenerator.generateModifiedTime());
    	List<DcTbl> relatedDcs = dcService.findClusterRelatedDc(clusterName);
    	
    	final ClusterTbl queryProto = proto;
    	queryHandler.handleQuery(new DalQuery<Integer>() {
			@Override
			public Integer doQuery() throws DalException {
				return clusterDao.deleteCluster(queryProto);
			}
    	});
    	
    	/** Notify meta server **/
    	notifier.notifyClusterDelete(clusterName, relatedDcs);
	}

	@Override
	public void bindDc(String clusterName, String dcName) {
		final ClusterTbl cluster = find(clusterName);
    	final DcTbl dc = dcService.find(dcName);
    	if(null == dc || null == cluster) throw new BadRequestException("Cannot bind dc due to unknown dc or cluster");
    	
    	queryHandler.handleQuery(new DalQuery<Integer>() {
			@Override
			public Integer doQuery() throws DalException {
				return clusterDao.bindDc(cluster, dc);
			}
    	});
	}

	@Override
	public void unbindDc(String clusterName, String dcName) {
		final ClusterTbl cluster = find(clusterName);
    	final DcTbl dc = dcService.find(dcName);
    	if(null == dc || null == cluster) throw new BadRequestException("Cannot unbind dc due to unknown dc or cluster");
    	
    	queryHandler.handleQuery(new DalQuery<Integer>() {
			@Override
			public Integer doQuery() throws DalException {
				return clusterDao.unbindDc(cluster, dc);
			}
    	});
    	
    	/** Notify meta server **/
    	notifier.notifyClusterDelete(clusterName, Arrays.asList(new DcTbl[]{dc}));
    	
	}

	@Override
	public void update(final ClusterTbl cluster) {
		queryHandler.handleQuery(new DalQuery<Integer>() {
			@Override
			public Integer doQuery() throws DalException {
				dao.updateByPK(cluster, ClusterTblEntity.UPDATESET_FULL);
				return 0;
			}
    	});
	}

	public static final Pattern VALID_EMAIL_ADDRESS_REGEX =
			Pattern.compile("^[A-Z0-9._%+-]+@Ctrip.com$", Pattern.CASE_INSENSITIVE);


	public boolean checkEmails(String emails) {
		if(emails == null || emails.trim().isEmpty()) {
			return false;
		}
		String splitter = "\\s*(,|;)\\s*";
		String[] emailStrs = StringUtil.splitRemoveEmpty(splitter, emails);
		for(String email : emailStrs) {
			if(!checkEmail(email))
				return false;
		}
		return true;
	}

	private boolean checkEmail(String email) {
		Matcher matcher = VALID_EMAIL_ADDRESS_REGEX.matcher(email);
		return matcher.find();
	}

	/**
	 * Randomly re-balance sentinel assignment for clusters among dcs
     * */
	@Override
	public List<String> reBalanceSentinels(final int numOfClusters) {
		List<String> clusters = randomlyChosenClusters(findAllClusterNames(), numOfClusters);
		logger.info("[reBalanceSentinels] pick up clusters: {}", clusters);

		doReBalance(clusters);
		return clusters;
	}

    // randomly get 'numOfClusters' cluster names
	private List<String> randomlyChosenClusters(final List<String> clusters, final int num) {
		if(clusters == null || clusters.isEmpty() || num >= clusters.size()) return clusters;
		if(random == null) {
			random = new Random();
		}
		int bound = clusters.size(), index = random.nextInt(bound);
		Set<String> result = new HashSet<>();
		for(int count = 0; count < num; count++) {
			while (index < 0 || !result.add(clusters.get(index))) {
				index = random.nextInt(bound);
			}
		}
		return new LinkedList<>(result);
	}

	@VisibleForTesting
	protected void doReBalance(final List<String> clusters) {
        List<String> dcNames = dcService.findAllDcNames().stream()
                .map(dcTbl -> dcTbl.getDcName()).collect(Collectors.toList());
        Map<String, List<SetinelTbl>> dcToSentinels = getDcNameMappedSentinels(dcNames);
        for(String cluster : clusters) {
            balanceCluster(dcToSentinels, cluster);
        }
	}

	// Cache {dc name} -> List {SentinelTbl}
	private Map<String, List<SetinelTbl>> getDcNameMappedSentinels(final List<String> dcNames) {
	    Map<String, List<SetinelTbl>> map = Maps.newHashMap();
	    for(String dc : dcNames) {
            List<SetinelTbl> sentinels = sentinelService.findAllByDcName(dc);
            map.put(dc, sentinels);
        }
        return map;
    }

    // Add transaction for one cluster update, rollback if one 'DcClusterShard' update fails
	@VisibleForTesting
	@DalTransaction
	protected void balanceCluster(Map<String, List<SetinelTbl>> dcToSentinels, final String cluster) {

		for(String dcName : dcToSentinels.keySet()) {
			List<DcClusterShardTbl> dcClusterShards = dcClusterShardService.findAllByDcCluster(dcName, cluster);
			List<SetinelTbl> sentinels = dcToSentinels.get(dcName);
			if(dcClusterShards == null || sentinels == null) {
				throw new XpipeRuntimeException("DcClusterShard | Sentinels should not be null");
			}
            long randomlySelectedSentinelId = randomlyChoseSentinels(sentinels);
			dcClusterShards.forEach(dcClusterShard -> {
				dcClusterShard.setSetinelId(randomlySelectedSentinelId);
				try {
					dcClusterShardService.updateDcClusterShard(dcClusterShard);
				} catch (DalException e) {
					throw new XpipeRuntimeException(e.getMessage());
				}
			});
		}
	}

	@VisibleForTesting
	protected long randomlyChoseSentinels(List<SetinelTbl> sentinels) {
		int randomNum = Math.abs(random.nextInt());
		int randomIndex = randomNum % sentinels.size();
		return sentinels.get(randomIndex).getSetinelId();
	}
}
