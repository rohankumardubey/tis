/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.qlangtech.tis;

import com.qlangtech.tis.collectinfo.CoreStatisticsReport;
import com.qlangtech.tis.collectinfo.api.ICoreService;
import com.qlangtech.tis.collectinfo.api.ICoreStatistics;
import com.qlangtech.tis.dataplatform.dao.IClusterSnapshotDAO;
import com.qlangtech.tis.dataplatform.pojo.ClusterSnapshot;
import com.qlangtech.tis.manage.biz.dal.dao.IApplicationDAO;
import com.qlangtech.tis.manage.biz.dal.pojo.Application;
import com.qlangtech.tis.manage.biz.dal.pojo.ApplicationCriteria;
import com.qlangtech.tis.manage.common.SendSMSUtils;
import com.qlangtech.tis.realtime.utils.NetUtils;
import com.tis.zookeeper.ZkPathUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.solr.common.cloud.*;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.SessionExpiredException;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * @author ?????????baisui@qlangtech.com???
 * @date 2013-5-7
 */
public class TSearcherClusterInfoCollect implements // Daemon
        InitializingBean, ICoreService {

    private IClusterSnapshotDAO clusterSnapshotDAO;

    private IApplicationDAO applicationDAO;

    public TSearcherClusterInfoCollect() {
        super();
    }

    private static final Logger log = LoggerFactory.getLogger(TSearcherClusterInfoCollect.class);

    static {
        // AbstractTisCloudSolrClient.initHashcodeRouter();
        // configFetcher = TSearcherConfigFetcher.get();
    }

    private static final String COLLECT_STATE_PATH;

    static {
        COLLECT_STATE_PATH = "/terminator-lock/cluster_state_collect_lock" + StringUtils.trimToEmpty(System.getProperty("collect_prject"));
    }

    // ??????????????????????????????3??????????????????
    public static final int COLLECT_STATE_INTERVAL = 180;

    @Override
    public void afterPropertiesSet() throws Exception {
        if (this.getClusterSnapshotDAO() == null) {
            throw new IllegalStateException("clusterSnapshotDAO can not be null");
        }
        // log.info("zk address:" + configFetcher.getZkAddress());
        // start();
        log.info("initialization has complete successful");
    }

    // public static IJobMetaDataDAO getJobMetaDataDAO() {
    // return staticFacade.getJobMetaDataDAO();
    // }
    //
    // public static ITriggerJobDAO getTriggerJobDAO() {
    // return staticFacade.getTriggerJobDAO();
    // }
    private Map<String, Application> /* index Name */
            appNamesMap;

    /**
     * ????????????????????? ?????????????????????
     *
     * @return
     */
    private Map<String, /* index Name */
            Application> getAppNameMap() {
        if (appNamesMap == null) {
            synchronized (TSearcherClusterInfoCollect.class) {
                if (appNamesMap == null) {
                    appNamesMap = new HashMap<String, Application>();
                    ApplicationCriteria query = new ApplicationCriteria();
                    List<Application> apps = this.getApplicationDAO().selectByExample(query, 1, 500);
                    for (Application app : apps) {
                        appNamesMap.put(app.getProjectName(), app);
                    }
                }
            }
        }
        return appNamesMap;
    }

    private static final CoreStatisticsReportHistory coreStatisticsReportHistory = new CoreStatisticsReportHistory();

    public CoreStatisticsReportHistory getCoreStatisticsReportHistory() {
        synchronized (coreStatisticsReportHistory) {
            return coreStatisticsReportHistory;
        }
    }

    public IApplicationDAO getApplicationDAO() {
        return this.applicationDAO;
    }

    /**
     * ?????????????????????????????????????????????????????????5???????????????
     */
    private void collectDataFromCluster() throws IOException {
        // ????????????,????????????????????????
        List<ClusterSnapshot> snapshot = createNewSnapshot();
        if (snapshot.size() < 1) {
            return;
        }
        List<ClusterSnapshot> records = snapshot.stream()
                .filter((sn) -> (sn.getIncrNumber() != null && sn.getIncrNumber() > 0))
                .collect(Collectors.toList()); // new ArrayList<>();
//        for (ClusterSnapshot sn : snapshot) {
//            if (sn.getIncrNumber() != null && sn.getIncrNumber() > 0) {
//                records.add(sn);
//            }
//        }
        if (records.size() > 0) {
            log.info("will create " + snapshot.size() + " serarch apps info into cluster records size : " + records.size());
            this.getClusterSnapshotDAO().insertList(records);
        }
    }

    // boolean flag = false;
    protected void writeCoreInfoToTair(CoreStatisticsReport report) {
    }

    /**
     * ????????????????????????????????????
     *
     * @return
     * @throws IOException
     */
    private List<ClusterSnapshot> createNewSnapshot() throws IOException {
        synchronized (coreStatisticsReportHistory) {
            final ZkStateReader cloudState = this.getCloudState();
            ClusterState clusterState = cloudState.getClusterState();
            Map<String, DocCollection> collectionMap = clusterState.getCollectionsMap();
            Map<String, CoreStatisticsReport> coreStatisticsReportMap = new HashMap<String, CoreStatisticsReport>(32);
            DocCollection c = null;
            for (Map.Entry<String, DocCollection> entry : collectionMap.entrySet()) {
                c = entry.getValue();
                for (Slice slice : c.getActiveSlices()) {
                    CoreStatisticsReport report = coreStatisticsReportMap.get(entry.getKey());
                    if (report == null) {
                        report = new CoreStatisticsReport(entry.getKey());
                        coreStatisticsReportMap.put(entry.getKey(), report);
                    }
                    report.addClusterCoreInfo(slice);
                }
            }
            final List<ClusterSnapshot> insertList = new ArrayList<>();
            int coreCount = 0;
            Map<Integer, ICoreStatistics> preCollectInfo = getPreCollectStatisticsReport();
            coreStatisticsReportHistory.clear();
            CoreStatisticsReport preReport = null;
            boolean preSnapshotNull = false;
            for (Map.Entry<String, CoreStatisticsReport> reportEntry : coreStatisticsReportMap.entrySet()) {
                String serviceName = reportEntry.getKey();
                CoreStatisticsReport report = reportEntry.getValue();
                if (!clusterContainApp(serviceName)) {
                    String msg = "collection:" + serviceName + " is not exist tis metadata,have been delete?";
                    SendSMSUtils.send(msg, SendSMSUtils.BAISUI_PHONE);
                    log.warn(msg);
                    continue;
                }
                ICoreStatistics pre = preCollectInfo.get(getAppId(serviceName));
                if (pre == null) {
                    preSnapshotNull = true;
                    coreCount++;
                    // ???????????????????????????????????????????????????????????????
                    coreStatisticsReportHistory.put(this.getAppId(serviceName), report);
                    continue;
                }
                preReport = ((CoreStatisticsReport) pre);
                final long incrRequest = preReport.getRequestIncreasement(report);
                insertList.add(createCollectPoint(serviceName, RecordExecType.QUERY, incrRequest));
                insertList.add(createCollectPoint(serviceName, RecordExecType.UPDATE, preReport.getUpdateCountIncreasement(report)));
                insertList.add(createCollectPoint(serviceName, RecordExecType.QUERY_ERROR, preReport.getQueryErrorCountIncreasement(report)));
                insertList.add(this.createCollectPoint(serviceName, RecordExecType.UPDATE_ERROR, preReport.getUpdateErrorCountIncreasement(report)));
                // if (snapshot.getRequestCount() > 0) {
                // ??????list?????????????????????????????????????????????????????????
                coreCount++;
                coreStatisticsReportHistory.putIfAbsent(this.getAppId(serviceName), report);
            }
            coreStatisticsReportHistory.setAllCoreCount(coreCount);
//            try {
//                if (!preSnapshotNull) {
//                    log.info("start vaildateUpdateCount");
//                    vaildateUpdateCount(collectionMap.keySet(), insertList);
//                } else {
//                    log.info("preSnapshotNull is true,so ignor this validate");
//                }
//            } catch (Throwable e) {
//                log.error(e.getMessage(), e);
//            }
            return insertList;
        }
    }

//    /**
//     * ??????tis??????????????????
//     *
//     * @param collectionNames
//     * @param insertList
//     */
//    protected void vaildateUpdateCount(Set<String> collectionNames, final List<ClusterSnapshot> insertList) {
//        if (!isInMonitorTimeRegion()) {
//            return;
//        }
//        List<String> invalidCollection = new ArrayList<>();
//        // ????????????????????????
//        ConcurrentLinkedQueue<Integer> lastestUpdateCountQueue = null;
//        collection:
//        for (String collection : collectionNames) {
//            for (ClusterSnapshot state : insertList) {
//                if (!clusterContainApp(collection)) {
//                    String msg = "collection:" + collection + " is not exist tis metadata,have been delete?";
//                    SendSMSUtils.send(msg, SendSMSUtils.BAISUI_PHONE);
//                    log.warn(msg);
//                    continue;
//                }
//                if (this.getAppId(collection) == state.getAppId().longValue() && RecordExecType.UPDATE.getValue().equals(state.getDataType())) {
//                    lastestUpdateCountQueue = lastestUpdateCount.get(collection);
//                    if (lastestUpdateCountQueue == null) {
//                        lastestUpdateCountQueue = new ConcurrentLinkedQueue<Integer>();
//                        lastestUpdateCount.put(collection, lastestUpdateCountQueue);
//                    }
//                    lastestUpdateCountQueue.add(state.getIncrNumber());
//                    while (lastestUpdateCountQueue.size() > getMonitorTimerange(collection)) {
//                        lastestUpdateCountQueue.poll();
//                    }
//                    StringBuffer incrNumberDesc = new StringBuffer(collection);
//                    for (Integer incrNumber : lastestUpdateCountQueue) {
//                        incrNumberDesc.append(",").append(incrNumber);
//                    }
//                    log.info(incrNumberDesc.toString());
//                    Iterator<Integer> updateCountIterator = lastestUpdateCountQueue.iterator();
//                    int updateCount = 0;
//                    while (updateCountIterator.hasNext()) {
//                        if ((updateCount = updateCountIterator.next()) > 0) {
//                            log.info("collection:" + collection + ",updateCount:" + updateCount + " update statue is in regular status ");
//                            continue collection;
//                        }
//                    }
//                }
//            }
//            int pastTimeGap = 0;
//            if (lastestUpdateCount.get(collection) == null || (pastTimeGap = lastestUpdateCount.get(collection).size()) >= getMonitorTimerange(collection)) {
//                if (!isInIndexBackFlowState(collection)) {
//                    invalidCollection.add(collection);
//                    // if ("search4totalpay".equals(collection)) {
//                    // SendSMSUtils.send("collection search4totalpay incr pause!!!!", SendSMSUtils.HUOSHAO_PHONE);
//                    // }
//                    log.info(collection + ",timerage past:" + pastTimeGap + ",maybe some errors in update process");
//                }
//            } else {
//                log.info(collection + "timerage past:" + pastTimeGap + ",shall wait");
//            }
//        }
//        if (invalidCollection.size() > 0) {
//            StringBuffer cols = new StringBuffer();
//            for (String c : invalidCollection) {
//                cols.append(StringUtils.replaceOnce(c, "search4", "s4")).append(",");
//            }
//            SendSMSUtils.send("incr(" + invalidCollection.size() + ")" + cols + " pause!!!!!", SendSMSUtils.BAISUI_PHONE);
//            log.info("send sms alert msg");
//        }
//    }

    /**
     * ??????zk??????????????????????????????
     *
     * @param collection
     * @return
     * @throws KeeperException
     * @throws InterruptedException
     */
    protected boolean isInIndexBackFlowState(String collection) {
        try {
            final SolrZkClient zk = this.getZookeeper();
            if (!zk.exists(ZkPathUtils.getIndexBackflowSignalPath(collection), true)) {
                return false;
            }
            List<String> children = Collections.emptyList();
            try {
                children = zk.getChildren(ZkPathUtils.getIndexBackflowSignalPath(collection), null, true);
            } catch (Throwable e) {
                log.error(e.getMessage(), e);
            }
            long maxTimestamp = 0;
            long tmp;
            for (String c : children) {
                tmp = Long.parseLong(StringUtils.substringAfter(c, ZkPathUtils.INDEX_BACKFLOW_SIGNAL_PATH_SEQNODE_NAME));
                if (tmp > maxTimestamp) {
                    maxTimestamp = tmp;
                }
            }
            boolean isInIndexBackFlowState = !(System.currentTimeMillis() > (maxTimestamp + 1000 * 60 * 300));
            log.info("collection:" + collection + ",index backflow:" + maxTimestamp + ",isInIndexBackFlowState:" + isInIndexBackFlowState);
            // ???????????????????????????,?????????????????????????????????????????????
            return isInIndexBackFlowState;
        } catch (Throwable e) {
            log.error(e.getMessage(), e);
        }
        return false;
    }

    private static final int MONITOR_TIMERANGE = 4;

    private static final int MONITOR_TIMERANGE_9 = 10;

    private static final int MONITOR_TIMERANGE_30 = 70;

    // ???????????????????????????,????????????????????????,?????????12?????????????????????????????????,???ConcurrentLinkedQueue???????????????????????????
    private Map<String, ConcurrentLinkedQueue<Integer>> lastestUpdateCount = new HashMap<String, ConcurrentLinkedQueue<Integer>>();

    /**
     * ?????????????????????
     *
     * @param serviceName
     * @param incrRequest
     * @return
     */
    private ClusterSnapshot createCollectPoint(String serviceName, RecordExecType dataType, final long incrRequest) {
        ClusterSnapshot snapshot;
        snapshot = new ClusterSnapshot();
        snapshot.setIncrNumber((int) incrRequest);
        snapshot.setAppId((long) getAppId(serviceName));
        snapshot.setGmtCreate(new Date());
        snapshot.setDataType(dataType.getValue());
        return snapshot;
    }

    private boolean clusterContainApp(String serviceName) {
        return this.getAppNameMap().containsKey(serviceName);
    }

    /**
     * @param serviceName
     * @return
     */
    public Integer getAppId(String serviceName) {
        Application app = this.getAppNameMap().get(serviceName);
        if (app == null) {
            throw new IllegalStateException("app can not be null, servicename:" + serviceName);
        }
        return app.getAppId();
    }

    @SuppressWarnings("all")
    private Map<Integer, /* appid */
            ICoreStatistics> getPreCollectStatisticsReport() {
        synchronized (coreStatisticsReportHistory) {
            Map<Integer, ICoreStatistics> result = new HashMap<Integer, ICoreStatistics>();
            for (Map.Entry<Integer, ICoreStatistics> entry : coreStatisticsReportHistory.entrySetWithOutValidate()) {
                result.put(entry.getKey(), entry.getValue());
            }
            return result;
        }
    }

    private final AtomicLong lastCollectTimeStamp = new AtomicLong(Long.MAX_VALUE - TSearcherClusterInfoCollect.COLLECT_STATE_INTERVAL * 20 * 1000);

    public long getLastCollectTimeStamp() {
        return lastCollectTimeStamp.get();
    }

    private void start() throws Exception {
        log.info("start to collect cluster info");
        // ????????????????????????
        final Runnable task = new Runnable() {

            @Override
            public void run() {
                while (true) {
                    try {
                        if (hasGrantCollectLock()) {
                            log.info("let me collect the status info");
                            // ??????????????????
                            collectDataFromCluster();
                            // ????????????????????????,??????????????????
                            lastCollectTimeStamp.getAndSet(System.currentTimeMillis());
                        } else {
                            // ???????????????????????????????????? ????????????????????????????????????
                            createNewSnapshot();
                            log.info("the task lock has not grant");
                        }
                    } catch (SessionExpiredException e) {
                        log.error("an zookeeper session expired exception occure", e);
                    } catch (Throwable e) {
                        // ????????????????????????
                        log.error("this error shall skip", e);
                    }
                    try {
                        // TODO:?????????????????????????????????????????????10?????????????????????????????????????????????????????????30??????????????????
                        Thread.sleep(COLLECT_STATE_INTERVAL * 1000);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        };
        (new Thread(task)).start();
        // TODO:?????????????????????????????????????????????????????????????????????????????????
        final Runnable appnameMapClear = new Runnable() {

            @Override
            public void run() {
                while (true) {
                    synchronized (TSearcherClusterInfoCollect.class) {
                        // ????????????????????????
                        TSearcherClusterInfoCollect.this.appNamesMap = null;
                        log.info("execute TSearcherClusterInfoCollect.this.appNamesMap= null");
                    }
                    try {
                        // ?????????????????????
                        Thread.sleep(2 * 60 * 60 * 1000);
                    } catch (Exception e) {
                        // throw new RuntimeException(e);
                    }
                }
            }
        };
        (new Thread(appnameMapClear, "appnameMapClear-thread")).start();
    }

    /**
     * ???????????????????????????????????????
     *
     * @return
     */
    private boolean hasGrantCollectLock() throws SessionExpiredException {
        throw new UnsupportedOperationException();
//        try {
//            // ?????????????????????????????????
//            final Date now = new Date();
//            SolrZkClient zookeeper = getZookeeper();
//            if (!zookeeper.exists(COLLECT_STATE_PATH, true)) {
//                // ?????????????????????????????????????????????
//                ZkUtils.guaranteeExist( zookeeper, COLLECT_STATE_PATH);
//                zookeeper.create(COLLECT_STATE_PATH, parseCurrnetTimeStamp(now), CreateMode.EPHEMERAL, true);
//                log.info("create new lock path:" + COLLECT_STATE_PATH);
//                return true;
//            }
//            final Stat stat = new Stat();
//            final byte[] content = zookeeper.getData(COLLECT_STATE_PATH, null, stat, true);
//            final long lastExecuteTimeStamp = parseLatestExecuteTimeStamp(content);
//            if ((lastExecuteTimeStamp + (COLLECT_STATE_INTERVAL * 1000)) <= now.getTime()) {
//                // ???????????????????????????????????????
//                zookeeper.setData(COLLECT_STATE_PATH, parseCurrnetTimeStamp(now), stat.getVersion(), true);
//                log.info("update the lock path:" + COLLECT_STATE_PATH);
//                return true;
//            }
//            return false;
//        } catch (SessionExpiredException e) {
//            // zookeeper?????????????????????
//            throw e;
//        } catch (KeeperException e) {
//            log.warn("zookeeper error", e);
//            return false;
//        } catch (Exception e) {
//            throw new RuntimeException(e);
//        }
    }

    // /**
    // * @return
    // */
    private SolrZkClient getZookeeper() {
        return this.zkStateReader.getZkClient();
    }

    private final String TIMESTAMP_PROPERTY = "timestamp";

    /**
     * ????????????????????????????????????timestamp
     *
     * @return
     */
    private long parseLatestExecuteTimeStamp(byte[] content) throws JSONException {
        try {
            JSONTokener tokener = new JSONTokener(new String(content));
            JSONObject json = new JSONObject(tokener);
            return Long.parseLong(json.getString(TIMESTAMP_PROPERTY));
        } catch (Throwable e) {
        }
        return 0;
    }

    private byte[] parseCurrnetTimeStamp(Date date) throws JSONException {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMddHHmm");
        JSONObject result = new JSONObject();
        result.put("view_time", format.format(date));
        result.put(TIMESTAMP_PROPERTY, String.valueOf(date.getTime()));
        result.put("execute_ip", NetUtils.getHost());
        return result.toString().getBytes();
    }

    public void setApplicationDAO(IApplicationDAO applicationDAO) {
        this.applicationDAO = applicationDAO;
    }

    public void setClusterSnapshotDAO(IClusterSnapshotDAO clusterSnapshotDAO) {
        this.clusterSnapshotDAO = clusterSnapshotDAO;
    }

    public IClusterSnapshotDAO getClusterSnapshotDAO() {
        return this.clusterSnapshotDAO;
    }

    /**
     *
     */
    public // Map<String, List<App>>
    BuAppMap getBuAppMap() {
        BuAppMap buAppMap = new BuAppMap();
        ApplicationCriteria query = new ApplicationCriteria();
        List<Application> apps = getApplicationDAO().selectByExample(query);
        List<App> applist = null;
        for (Application ap : apps) {
            App pp = new App();
            pp.setAppid(ap.getAppId());
            pp.setDpt(ap.getDptName());
            pp.setServiceName(ap.getProjectName());
            if ((applist = buAppMap.get(pp.getBu())) == null) {
                applist = new ArrayList<App>();
                buAppMap.put(pp.getBu(), applist);
            }
            applist.add(pp);
        }
        return buAppMap;
    }

    // private CloudSolrClient solrClient;
    private ZkStateReader zkStateReader;

    public ZkStateReader getCloudState() {
        if (zkStateReader == null) {
            throw new IllegalStateException("zkStateReader can not be null");
        }
        return zkStateReader;
    }

    public void setZkStateReader(ZkStateReader zkStateReader) {
        this.zkStateReader = zkStateReader;
    }

    private int getMonitorTimerange(String collection) {
        // ??????0~7 timerange ?????????8,??????????????????????????????????????????????????????????????????
        int hourOfDay = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hourOfDay >= 0 && hourOfDay < 3) {
            return MONITOR_TIMERANGE_9;
        }
        if (hourOfDay >= 3 && hourOfDay < 7) {
            return MONITOR_TIMERANGE_30;
        }
        if (hourOfDay >= 7 && hourOfDay < 9) {
            return MONITOR_TIMERANGE_9 + 3;
        }
        if (hourOfDay >= 22) {
            return MONITOR_TIMERANGE_9;
        }
        return MONITOR_TIMERANGE;
    }

    /**
     * ???????????????????????????
     *
     * @return
     */
    private boolean isInMonitorTimeRegion() {
        int hourOfDay = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        boolean in = (hourOfDay >= 10 && hourOfDay < 15) || (hourOfDay >= 17 && hourOfDay < 22);
        log.info("now hour is:" + hourOfDay + (in ? " shall monitor" : " will ignore"));
        return in;
    }

    public static void main(String[] args) {
        System.out.println(Calendar.getInstance().get(Calendar.HOUR_OF_DAY));
        System.out.println(Long.parseLong("000000000"));
        System.out.println(Long.parseLong("000000001"));
    }
}
