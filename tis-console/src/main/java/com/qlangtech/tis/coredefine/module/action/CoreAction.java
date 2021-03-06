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
package com.qlangtech.tis.coredefine.module.action;

import com.alibaba.citrus.turbine.Context;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.parser.Feature;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.koubei.web.tag.pager.Pager;
import com.qlangtech.tis.TIS;
import com.qlangtech.tis.assemble.ExecResult;
import com.qlangtech.tis.cloud.ICoreAdminAction;
import com.qlangtech.tis.cloud.ITISCoordinator;
import com.qlangtech.tis.compiler.streamcode.GenerateDAOAndIncrScript;
import com.qlangtech.tis.compiler.streamcode.IndexStreamCodeGenerator;
import com.qlangtech.tis.coredefine.biz.FCoreRequest;
import com.qlangtech.tis.coredefine.module.action.impl.FlinkJobDeploymentDetails;
import com.qlangtech.tis.coredefine.module.action.impl.RcDeployment;
import com.qlangtech.tis.coredefine.module.control.SelectableServer;
import com.qlangtech.tis.coredefine.module.control.SelectableServer.CoreNode;
import com.qlangtech.tis.coredefine.module.screen.Corenodemanage.InstanceDirDesc;
import com.qlangtech.tis.coredefine.module.screen.Corenodemanage.ReplicState;
import com.qlangtech.tis.datax.impl.DataxProcessor;
import com.qlangtech.tis.datax.impl.DataxReader;
import com.qlangtech.tis.datax.impl.DataxWriter;
import com.qlangtech.tis.exec.IExecChainContext;
import com.qlangtech.tis.extension.Descriptor;
import com.qlangtech.tis.fullbuild.IFullBuildContext;
import com.qlangtech.tis.lang.TisException;
import com.qlangtech.tis.manage.IAppSource;
import com.qlangtech.tis.manage.IBasicAppSource;
import com.qlangtech.tis.manage.ISolrAppSource;
import com.qlangtech.tis.manage.PermissionConstant;
import com.qlangtech.tis.manage.biz.dal.pojo.*;
import com.qlangtech.tis.manage.common.*;
import com.qlangtech.tis.manage.common.ConfigFileContext.StreamProcess;
import com.qlangtech.tis.manage.common.HttpUtils.PostParam;
import com.qlangtech.tis.manage.impl.DataFlowAppSource;
import com.qlangtech.tis.manage.impl.SingleTableAppSource;
import com.qlangtech.tis.manage.servlet.QueryCloudSolrClient;
import com.qlangtech.tis.manage.servlet.QueryIndexServlet;
import com.qlangtech.tis.manage.servlet.QueryResutStrategy;
import com.qlangtech.tis.manage.spring.aop.Func;
import com.qlangtech.tis.order.center.IAppSourcePipelineController;
import com.qlangtech.tis.order.center.IParamContext;
import com.qlangtech.tis.plugin.IPluginStore;
import com.qlangtech.tis.plugin.incr.IncrStreamFactory;
import com.qlangtech.tis.pubhook.common.RunEnvironment;
import com.qlangtech.tis.runtime.module.action.BasicModule;
import com.qlangtech.tis.runtime.module.action.ClusterStateCollectAction;
import com.qlangtech.tis.runtime.module.screen.BasicScreen;
import com.qlangtech.tis.runtime.module.screen.ViewPojo;
import com.qlangtech.tis.runtime.pojo.ServerGroupAdapter;
import com.qlangtech.tis.solrj.util.ZkUtils;
import com.qlangtech.tis.sql.parser.DBNode;
import com.qlangtech.tis.sql.parser.stream.generate.FacadeContext;
import com.qlangtech.tis.sql.parser.stream.generate.StreamCodeContext;
import com.qlangtech.tis.sql.parser.tuple.creator.IStreamIncrGenerateStrategy;
import com.qlangtech.tis.util.DescriptorsJSON;
import com.qlangtech.tis.web.start.TisAppLaunchPort;
import com.qlangtech.tis.workflow.dao.IWorkFlowBuildHistoryDAO;
import com.qlangtech.tis.workflow.pojo.*;
import org.apache.commons.lang.StringUtils;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.XMLResponseParser;
import org.apache.solr.common.cloud.*;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.zookeeper.data.Stat;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.apache.solr.common.cloud.ZkStateReader.BASE_URL_PROP;
import static org.apache.solr.common.cloud.ZkStateReader.CORE_NAME_PROP;

//import com.qlangtech.tis.realtime.yarn.rpc.JobType;

/**
 * core ????????????
 *
 * @author ?????????baisui@qlangtech.com???
 * @date 2016???8???4???
 */
public class CoreAction extends BasicModule {
  public static final String ADMIN_COLLECTION_PATH = "/solr/admin/collections";
  public static final String CREATE_COLLECTION_PATH = ADMIN_COLLECTION_PATH + "?action=CREATE&name=";
  public static final String TRIGGER_FULL_BUILD_COLLECTION_PATH = "/trigger";

  public static final String DEFAULT_SOLR_CONFIG = "tis_mock_config";

  private static final long serialVersionUID = -6753169329484480543L;

  private static final Pattern PATTERN_IP = Pattern.compile("^((\\d+?).(\\d+?).(\\d+?).(\\d+?)):(\\d+)_solr$");

  // private final String CLIENT_ZK_PATH = "/terminator/dump-controller/";
  public static final XMLResponseParser RESPONSE_PARSER = new XMLResponseParser();

  private static final int MAX_SHARDS_PER_NODE = 16;

  private static final Logger log = LoggerFactory.getLogger(CoreAction.class);

  private static final Pattern placehold_pattern = Pattern.compile("\\$\\{(.+)\\}");

  public static final String CREATE_CORE_SELECT_COREINFO = "selectCoreinfo";


  /**
   * ???????????????????????????
   *
   * @param context
   * @throws Exception
   */
  @Func(value = PermissionConstant.APP_UPDATE)
  public void doCancelTask(Context context) throws Exception {
    Integer taskId = this.getInt(IExecChainContext.KEY_TASK_ID);

    IWorkFlowBuildHistoryDAO workFlowBuildDAO = this.getWorkflowDAOFacade().getWorkFlowBuildHistoryDAO();
    WorkFlowBuildHistory buildHistory = workFlowBuildDAO.loadFromWriteDB(taskId);
    ExecResult processState = ExecResult.parse(buildHistory.getState());
    if (!processState.isProcessing()) {
      this.addErrorMessage(context, "?????????????????????????????????????????????????????????");
      return;
    }

    List<ConfigFileContext.Header> headers = Lists.newArrayList();
    headers.add(new ConfigFileContext.Header(IExecChainContext.KEY_TASK_ID, String.valueOf(taskId)));
    headers.add(new ConfigFileContext.Header(IParamContext.KEY_ASYN_JOB_NAME, String.valueOf(processState == ExecResult.ASYN_DOING)));
    headers.add(new ConfigFileContext.Header(IFullBuildContext.KEY_APP_NAME, IAppSourcePipelineController.DATAX_FULL_PIPELINE + buildHistory.getAppName()));

    TriggerBuildResult triggerResult = CoreAction.triggerBuild(this, context, ConfigFileContext.HTTPMethod.DELETE, Collections.emptyList(), headers);
    if (!triggerResult.success) {
      return;
    }

    WorkFlowBuildHistory record = new WorkFlowBuildHistory();
    record.setState((byte) ExecResult.CANCEL.getValue());

    WorkFlowBuildHistoryCriteria criteria = new WorkFlowBuildHistoryCriteria();
    criteria.createCriteria().andIdEqualTo(triggerResult.getTaskid());

    workFlowBuildDAO.updateByExampleSelective(record, criteria);
    this.addActionMessage(context, "??????????????????????????????");
    this.setBizResult(context, new ExtendWorkFlowBuildHistory(
      this.getWorkflowDAOFacade().getWorkFlowBuildHistoryDAO().loadFromWriteDB(triggerResult.getTaskid())));
  }

  /**
   * ??????????????????????????????
   *
   * @param context
   * @throws Exception
   */
  public void doRelaunchIncrProcess(Context context) throws Exception {
    IPluginStore<IncrStreamFactory> incrStreamStore = getIncrStreamFactoryStore(this, true);
    IncrStreamFactory incrStream = incrStreamStore.getPlugin();
    IRCController incrSync = incrStream.getIncrSync();
    incrSync.relaunch(new TargetResName(this.getCollectionName()));
  }

  /**
   * ?????????????????????????????????
   *
   * @param context
   */
  public void doGetIncrStatus(Context context) throws Exception {
    final boolean cache = this.getBoolean("cache");
    IndexIncrStatus incrStatus = getIndexIncrStatus(this, cache);
    this.setBizResult(context, incrStatus);
  }

  public static IndexIncrStatus getIndexIncrStatus(BasicModule module, boolean getRcConfigInCache) throws Exception {
    IndexIncrStatus incrStatus = new IndexIncrStatus();
    doGetDataXReaderWriterDesc(module.getCollectionName(), incrStatus);
    // ???????????????????????????deployment?????????????????????pod??????????????????????????????deployment??????????????????
    IPluginStore<IncrStreamFactory> store = getIncrStreamFactoryStore(module);
    if (store.getPlugin() == null) {
      incrStatus.setK8sPluginInitialized(false);
      return incrStatus;
    }
    incrStatus.setK8sPluginInitialized(true);
    IndexStreamCodeGenerator indexStreamCodeGenerator = getIndexStreamCodeGenerator(module);
    StreamCodeContext streamCodeContext
      = new StreamCodeContext(module.getCollectionName(), indexStreamCodeGenerator.incrScriptTimestamp);
    incrStatus.setIncrScriptCreated(streamCodeContext.isIncrScriptDirCreated());
    TISK8sDelegate k8s = TISK8sDelegate.getK8SDelegate(module.getCollectionName());
    IDeploymentDetail rcConfig = k8s.getRcConfig(getRcConfigInCache);
    incrStatus.setK8sReplicationControllerCreated(rcConfig != null);
    if (rcConfig != null) {
      rcConfig.accept(new IDeploymentDetail.IDeploymentDetailVisitor() {
        @Override
        public void visit(RcDeployment rcDeployment) {
          incrStatus.setRcDeployment(rcDeployment);
        }

        @Override
        public void visit(FlinkJobDeploymentDetails details) {
          incrStatus.setFlinkJobDetail(details);
        }
      });

//      JobType.RemoteCallResult<IndexJobRunningStatus> callResult
//        = JobType.QueryIndexJobRunningStatus.assembIncrControlWithResult(
//        getAssembleNodeAddress(module.getSolrZkClient()),
//        module.getCollectionName(), Collections.emptyList(), IndexJobRunningStatus.class);
//      if (callResult.success) {
      incrStatus.setIncrProcess(null);
      //}
    }
    return incrStatus;
  }

  public static IPluginStore<IncrStreamFactory> getIncrStreamFactoryStore(BasicModule module) {
    return getIncrStreamFactoryStore(module, false);
  }

  private static IPluginStore<IncrStreamFactory> getIncrStreamFactoryStore(BasicModule module, boolean validateNull) {
    IPluginStore<IncrStreamFactory> store = TIS.getPluginStore(module.getCollectionName(), IncrStreamFactory.class);
    if (validateNull && store.getPlugin() == null) {
      throw new IllegalStateException("collection:" + module.getCollectionName() + " relevant IncrStreamFactory store can not be null");
    }
    return store;
  }

  /**
   * ?????????????????? <br>
   * 1. ??????dao???????????????????????? <br>
   * 2. ?????????????????????????????? <br>
   * 3. ???????????????????????????????????????????????????????????????????????? <br>
   * 4. ?????????????????????????????? <br>
   * 5. ???????????????????????????scala???????????? <br>
   * 6. ??????????????????????????????????????????  <br>
   * 7. ????????????????????????????????????????????????????????????????????????????????? <br>
   * 8. ?????????????????????????????? <br>
   * 9. ??????dao???????????????scala???????????????????????????????????????????????????docker????????????????????????????????????k8s????????????<br>
   */
  @Func(value = PermissionConstant.PERMISSION_INCR_PROCESS_CONFIG_EDIT, sideEffect = false)
  public void doCreateIncrSyncChannal(Context context) throws Exception {
    IndexIncrStatus incrStatus = generateDAOAndIncrScript(this, context);
    this.setBizResult(context, incrStatus);
  }

  public static IndexIncrStatus generateDAOAndIncrScript(BasicModule module, Context context) throws Exception {
    return generateDAOAndIncrScript(module, context, false, false);
  }

  public static IndexIncrStatus generateDAOAndIncrScript(
    BasicModule module, Context context, boolean validateGlobalIncrStreamFactory, boolean compilerAndPackage) throws Exception {

    IStreamIncrGenerateStrategy appSource = IAppSource.load(null, module.getCollectionName());

    return generateDAOAndIncrScript(module, context
      , validateGlobalIncrStreamFactory, compilerAndPackage, appSource.isExcludeFacadeDAOSupport());
  }

  /**
   * @param module
   * @param context
   * @param
   * @param validateGlobalIncrStreamFactory
   * @param compilerAndPackage
   * @param excludeFacadeDAOSupport         ???????????????????????????dao?????????????????????false??????
   * @return
   * @throws Exception
   */
  public static IndexIncrStatus generateDAOAndIncrScript(
    BasicModule module, Context context
    , boolean validateGlobalIncrStreamFactory, boolean compilerAndPackage, boolean excludeFacadeDAOSupport) throws Exception {


    IndexStreamCodeGenerator indexStreamCodeGenerator = getIndexStreamCodeGenerator(module);
    List<FacadeContext> facadeList = indexStreamCodeGenerator.getFacadeList();
    //PluginStore<IncrStreamFactory> store = TIS.getPluginStore(IncrStreamFactory.class);
    IndexIncrStatus incrStatus = new IndexIncrStatus();
    doGetDataXReaderWriterDesc(module.getCollectionName(), incrStatus);
//    if (validateGlobalIncrStreamFactory && store.getPlugin() == null) {
//      throw new IllegalStateException("global IncrStreamFactory config can not be null");
//    }

    //if (store.getPlugin() != null) {
    // ???????????????????????????
    IPluginStore<IncrStreamFactory> collectionBindIncrStreamFactoryStore = getIncrStreamFactoryStore(module);
    if (collectionBindIncrStreamFactoryStore.getPlugin() == null) {
      // ????????????????????????????????????collection???????????????????????????
//      Descriptor flinkStreamDesc = TIS.get().getDescriptor(IncrStreamFactory.FLINK_STREM);
//      if (flinkStreamDesc == null) {
//        throw new IllegalStateException(
//          "can not find findStream Factory in plugin repository, Descriptor ID:" + IncrStreamFactory.FLINK_STREM);
//      }
//
//      collectionBindIncrStreamFactoryStore.setPlugins(module, Optional.of(context)
//        , Collections.singletonList(flinkStreamDesc.newInstance(module, Collections.emptyMap(), Optional.empty())));
      //  throw new IllegalStateException("collectionName:" + module.getCollectionName() + " relevant plugin can not be null " + IncrStreamFactory.class.getName());
    }
    //}
    // ???????????????false??????
    incrStatus.setK8sPluginInitialized(false);
    // ??????????????????????????????????????????????????????????????????
    if (indexStreamCodeGenerator.isIncrScriptDirCreated()) {
      incrStatus.setIncrScriptCreated(true);
      incrStatus.setIncrScriptMainFileContent(indexStreamCodeGenerator.readIncrScriptMainFileContent());
      log.info("incr script has create ignore it file path:{}", indexStreamCodeGenerator.getIncrScriptDirPath());
      // this.setBizResult(context, incrStatus);
    }
    GenerateDAOAndIncrScript generateDAOAndIncrScript = new GenerateDAOAndIncrScript(module, indexStreamCodeGenerator);
    if (excludeFacadeDAOSupport) {
      generateDAOAndIncrScript.generateIncrScript(context, incrStatus, compilerAndPackage, Collections.emptyMap());
    } else {
      // ??????facadeDAO??????
      generateDAOAndIncrScript.generate(context, incrStatus
        , compilerAndPackage, getDependencyDbsMap(module, indexStreamCodeGenerator));
    }

    incrStatus.setIncrScriptTimestamp(indexStreamCodeGenerator.getIncrScriptTimestamp());
    return incrStatus;
  }


  private static void doGetDataXReaderWriterDesc(String appName, IndexIncrStatus incrStatus) throws Exception {
    DataxProcessor dataxProcessor = DataxProcessor.load(null, appName);
    DataxWriter writer = (DataxWriter) dataxProcessor.getWriter(null);
    incrStatus.setWriterDesc(createDescVals(writer.getDescriptor()));

    DataxReader reader = (DataxReader) dataxProcessor.getReader(null);
    incrStatus.setReaderDesc(createDescVals(reader.getDescriptor()));
  }

  private static HashMap<String, Object> createDescVals(Descriptor writerDesc) {
    HashMap<String, Object> newExtraProps = Maps.newHashMap(writerDesc.getExtractProps());
    newExtraProps.put(DescriptorsJSON.KEY_IMPL, writerDesc.getId());
    newExtraProps.put(DescriptorsJSON.KEY_EXTEND_POINT, writerDesc.getT().getName());
    return newExtraProps;
  }

  /**
   * ????????????
   *
   * @param context
   * @throws Exception
   */
  @Func(value = PermissionConstant.PERMISSION_INCR_PROCESS_CONFIG_EDIT)
  public void doCompileAndPackage(Context context) throws Exception {

    IBasicAppSource appSource = IAppSource.load(null, this.getCollectionName());

    IndexStreamCodeGenerator indexStreamCodeGenerator = getIndexStreamCodeGenerator(this);
    IndexIncrStatus incrStatus = new IndexIncrStatus();
    GenerateDAOAndIncrScript daoAndIncrScript = new GenerateDAOAndIncrScript(this, indexStreamCodeGenerator);

    if (appSource.isExcludeFacadeDAOSupport()) {
      daoAndIncrScript.generateIncrScript(context, incrStatus, true, Collections.emptyMap());
    } else {
      Map<Integer, Long> dependencyDbs = getDependencyDbsMap(this, indexStreamCodeGenerator);
      // ??????facadeDAO??????
      daoAndIncrScript.generate(context, incrStatus, true, dependencyDbs);
    }
  }

  /**
   * ????????????
   * https://nightlies.apache.org/flink/flink-docs-release-1.14/docs/deployment/resource-providers/standalone/kubernetes/
   *
   * @param context
   * @throws Exception
   */
  @Func(value = PermissionConstant.PERMISSION_INCR_PROCESS_MANAGE)
  public void doDeployIncrSyncChannal(Context context) throws Exception {
    // ?????????????????????
    StringBuffer logger = new StringBuffer("flin sync app:" + this.getCollectionName());
    try {
      long start = System.currentTimeMillis();
      this.doCompileAndPackage(context);
      if (context.hasErrors()) {
        return;
      }
      logger.append("\n compile and package consume:" + (System.currentTimeMillis() - start) + "ms ");
      // ??????????????????
      IndexStreamCodeGenerator indexStreamCodeGenerator = getIndexStreamCodeGenerator(this);

      // ?????????????????????????????????k8s????????????
      // https://github.com/kubernetes-client/java
      TISK8sDelegate k8sClient = TISK8sDelegate.getK8SDelegate(this.getCollectionName());
      start = System.currentTimeMillis();
      // ??????k8s??????
      k8sClient.deploy(null, indexStreamCodeGenerator.getIncrScriptTimestamp());
      logger.append("\n deploy to flink cluster consume:" + (System.currentTimeMillis() - start) + "ms ");
      IndexIncrStatus incrStatus = new IndexIncrStatus();
      this.setBizResult(context, incrStatus);
    } catch (Exception ex) {
      logger.append("an error occur:"+ ex.getMessage());
      throw new TisException(ex.getMessage(), ex);
    } finally {
      log.info(logger.toString());
    }
  }

  private static Map<Integer, Long> getDependencyDbsMap(BasicModule module, IndexStreamCodeGenerator indexStreamCodeGenerator) {
    final Map<DBNode, List<String>> dbNameMap = indexStreamCodeGenerator.getDbTables();
    if (dbNameMap.size() < 1) {
      throw new IllegalStateException("dbNameMap size can not small than 1");
    }
    // Map<Integer /**DBID*/, Long /**timestamp ver*/> dependencyDbs = null;
    DatasourceDbCriteria dbCriteria = new DatasourceDbCriteria();
    dbCriteria.createCriteria().andIdIn(dbNameMap.keySet().stream().map((dbNode -> dbNode.getDbId())).collect(Collectors.toList()));
    return module.getWorkflowDAOFacade().getDatasourceDbDAO()
      .selectByExample(dbCriteria).stream()
      .collect(Collectors.toMap(DatasourceDb::getId, (r) -> ManageUtils.formatNowYyyyMMddHHmmss(r.getOpTime())));
  }

  private static IndexStreamCodeGenerator getIndexStreamCodeGenerator(BasicModule module) throws Exception {
    //final WorkFlow workFlow = module.loadDF(workflowid);
    //SqlTaskNodeMeta.SqlDataFlowTopology wfTopology = SqlTaskNodeMeta.getSqlDataFlowTopology(workFlow.getName());
//    return getIndexStreamCodeGenerator(module);//, workFlow, wfTopology.isSingleDumpTableDependency());
//  }
//
//  public static IndexStreamCodeGenerator getIndexStreamCodeGenerator(
//    BasicModule module, WorkFlow workFlow, boolean excludeFacadeDAOSupport) throws Exception {

    IBasicAppSource appSource = IAppSource.load(null, module.getCollectionName());

    Date scriptLastOpTime = appSource.accept(new IBasicAppSource.IAppSourceVisitor<Date>() {
      @Override
      public Date visit(DataxProcessor app) {
        Application application = module.getApplicationDAO().selectByName(app.identityValue());
        return application.getUpdateTime();
      }

      @Override
      public Date visit(SingleTableAppSource single) {
        DatasourceTable table = module.getWorkflowDAOFacade().getDatasourceTableDAO().loadFromWriteDB(single.getTabId());
        return table.getOpTime();
      }

      @Override
      public Date visit(DataFlowAppSource dataflow) {
        Integer dfId = dataflow.getDfId();
        WorkFlow workFlow = module.getWorkflowDAOFacade().getWorkFlowDAO().loadFromWriteDB(dfId);
        return workFlow.getOpTime();
      }
    });
    return new IndexStreamCodeGenerator(module.getCollectionName(), appSource
      , ManageUtils.formatNowYyyyMMddHHmmss(scriptLastOpTime), (dbId, rewriteableTables) -> {
      // ??????dbid??????db?????????????????????
      DatasourceTableCriteria tableCriteria = new DatasourceTableCriteria();
      tableCriteria.createCriteria().andDbIdEqualTo(dbId);
      List<DatasourceTable> tableList = module.getWorkflowDAOFacade().getDatasourceTableDAO().selectByExample(tableCriteria);
      return tableList.stream().map((t) -> t.getName()).collect(Collectors.toList());
    }, appSource.isExcludeFacadeDAOSupport());
  }


  public static void main(String[] args) {

  }


  /**
   * ????????????????????????Task do_trigger_fullbuild_task
   *
   * @param context
   * @throws Exception
   */
  @Func(value = PermissionConstant.DATAFLOW_MANAGE)
  public void doTriggerFullbuildTask(Context context) throws Exception {
    Application app = this.getAppDomain().getApp();
    triggerFullIndexSwape(this, context, app, getIndex().getSlices().size());
  }


  /**
   * ????????????????????????
   *
   * @param module
   * @param context
   * @param app
   * @param sharedCount
   * @return
   * @throws Exception
   */
  public static TriggerBuildResult triggerFullIndexSwape(BasicModule module, Context context, Application app, int sharedCount) throws Exception {

    Objects.requireNonNull(app, "app can not be null");

//    Objects.requireNonNull(wfId, "wfId can not be null");
//    if (sharedCount < 1) {
//      throw new IllegalArgumentException("param sharedCount can not be null");
//    }
//    if (StringUtils.isEmpty(wfName)) {
//      throw new IllegalArgumentException("param wfName can not be null");
//    }

    ISolrAppSource appSource = IAppSource.load(null, app.getProjectName());

    if (!appSource.triggerFullIndexSwapeValidate(module, context)) {
      return new TriggerBuildResult(false);
    }

//    SqlTaskNodeMeta.SqlDataFlowTopology topology = SqlTaskNodeMeta.getSqlDataFlowTopology(wfName);
//    Objects.requireNonNull(topology, "topology:" + wfName + " relevant topology can not be be null");
//
//    Optional<ERRules> erRule = module.getErRules(wfName);
//    if (!topology.isSingleTableModel()) {
//      if (!erRule.isPresent()) {
//        module.addErrorMessage(context, "???????????????:[" + wfName + "]??????ER Rule");
//        return new TriggerBuildResult(false);
//      } else {
//        ERRules erRules = erRule.get();
//        List<PrimaryTableMeta> pTabs = erRules.getPrimaryTabs();
//        Optional<PrimaryTableMeta> prTableMeta = pTabs.stream().findFirst();
//        if (!TableMeta.hasValidPrimayTableSharedKey(prTableMeta.isPresent() ? Optional.of(prTableMeta.get()) : Optional.empty())) {
//          module.addErrorMessage(context, "???????????????:[" + wfName + "]??????ERRule ?????????????????????????????????");
//          return new TriggerBuildResult(false);
//        }
//      }
//    }
    return sendRequest2FullIndexSwapeNode(module, context, new AppendParams() {
      @Override
      List<PostParam> getParam() {
        return Lists.newArrayList(
//          new PostParam(IFullBuildContext.KEY_WORKFLOW_NAME, wfName)
//          , new PostParam(IFullBuildContext.KEY_WORKFLOW_ID, String.valueOf(wfId))
          // new PostParam(IFullBuildContext.KEY_APP_NAME, app.getProjectName())
          //,
          new PostParam(IFullBuildContext.KEY_APP_SHARD_COUNT, String.valueOf(sharedCount)));
      }
    });
  }

  /**
   * ??????taskid??????
   *
   * @param context
   * @throws Exception
   */
  public void doGetWorkflowBuildHistory(final Context context) throws Exception {
    Integer taskid = this.getInt(IParamContext.KEY_TASK_ID, null, false);
    WorkFlowBuildHistory buildHistory = this.getWorkflowDAOFacade().getWorkFlowBuildHistoryDAO().selectByPrimaryKey(taskid);
    if (buildHistory == null) {
      throw new IllegalStateException(IParamContext.KEY_TASK_ID + ":" + taskid + "relevant buildHistory can not be null");
    }
    this.setBizResult(context, new ExtendWorkFlowBuildHistory(buildHistory));
  }

  /**
   * ??????????????????dump
   *
   * @param context
   * @throws Exception
   */
  @Func(PermissionConstant.APP_TRIGGER_FULL_DUMP)
  public void doTriggerDump(final Context context) throws Exception {
    // 'http://localhost:14844/trigger?component.start=indexBackflow&ps=20151215155124'
    // ApplicationCriteria criteria = new ApplicationCriteria();
    // criteria.createCriteria().andProjectNameEqualTo(this.getServiceName());
    // List<Application> appList =
    // this.getApplicationDAO().selectByExample(criteria);
    // Assert.assertEquals(1, appList.size());
    boolean success = sendRequest2FullIndexSwapeNode(this, context, new AppendParams() {

      @Override
      List<PostParam> getParam() {
        return Collections.emptyList();
      }
    }).success;
    if (success) {
      this.addActionMessage(context, "?????????????????????DUMP(triggerServiceFullDump)");
    }
  }

  private static final String bizKey = "biz";
  public static final String KEY_APPNAME = "appname";

  /**
   * @param context
   * @param appendParams
   * @return
   * @throws MalformedURLException
   */
  private static TriggerBuildResult sendRequest2FullIndexSwapeNode(
    BasicModule module, final Context context, AppendParams appendParams) throws Exception {

    List<HttpUtils.PostParam> params = appendParams.getParam();
    params.add(new PostParam(KEY_APPNAME, module.getCollectionName()));
    return triggerBuild(module, context, params);
  }

  public static TriggerBuildResult triggerBuild(
    BasicModule module, final Context context, List<PostParam> appendParams) throws MalformedURLException {
    return triggerBuild(module, context, ConfigFileContext.HTTPMethod.POST, appendParams, Collections.emptyList());
  }


  public static TriggerBuildResult triggerBuild(
    BasicModule module, final Context context, ConfigFileContext.HTTPMethod httpMethod, List<PostParam> appendParams
    , List<ConfigFileContext.Header> headers) throws MalformedURLException {
    final String assembleNodeAddress = getAssembleNodeAddress(module.getSolrZkClient());

    TriggerBuildResult triggerResult
      = HttpUtils.process(new URL(assembleNodeAddress + TRIGGER_FULL_BUILD_COLLECTION_PATH)
      , appendParams, new PostFormStreamProcess<TriggerBuildResult>() {
        @Override
        public List<ConfigFileContext.Header> getHeaders() {
          return headers;
        }

        @Override
        public ContentType getContentType() {
          return ContentType.Application_x_www_form_urlencoded;
        }

        @Override
        public TriggerBuildResult p(int status, InputStream stream, Map<String, List<String>> headerFields) {
          TriggerBuildResult triggerResult = null;
          try {
            JSONTokener token = new JSONTokener(stream);
            JSONObject result = new JSONObject(token);
            final String successKey = "success";
            if (result.isNull(successKey)) {
              return new TriggerBuildResult(false);
            }
            triggerResult = new TriggerBuildResult(true);
            if (!result.isNull(bizKey)) {
              JSONObject o = result.getJSONObject(bizKey);
              if (!o.isNull(IParamContext.KEY_TASK_ID)) {
                triggerResult.taskid = Integer.parseInt(o.getString(IParamContext.KEY_TASK_ID));
              }
              module.setBizResult(context, o);
            }
            if (result.getBoolean(successKey)) {
              return triggerResult;
            }
            module.addErrorMessage(context, result.getString("msg"));
            return new TriggerBuildResult(false);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }
      }, httpMethod);
    return triggerResult;
  }

  public static String getAssembleNodeAddress(ITISCoordinator coordinator) {
    // ????????????????????????
    final String incrStateCollectAddress =
      ZkUtils.getFirstChildValue(
        coordinator,
        ZkUtils.ZK_ASSEMBLE_LOG_COLLECT_PATH,
        null, true);
    return "http://" + StringUtils.substringBefore(incrStateCollectAddress, ":")
      + ":"+ TisAppLaunchPort.getPort() + Config.CONTEXT_ASSEMBLE;
  }

  public static class TriggerBuildResult {

    public final boolean success;

    private int taskid;

    public TriggerBuildResult(boolean success) {
      this.success = success;
    }

    public int getTaskid() {
      return taskid;
    }
  }

  // #################################################################################
  public void doGetWorkflow(Context context) throws Exception {
    Integer wfid = this.getInt("wfid");
    Integer taskid = this.getInt(IParamContext.KEY_TASK_ID);
    WorkFlow workFlow = this.getWorkflowDAOFacade().getWorkFlowDAO().selectByPrimaryKey(wfid);
    WorkFlowBuildHistory buildHistory = this.getWorkflowDAOFacade().getWorkFlowBuildHistoryDAO().selectByPrimaryKey(taskid);
    Map<String, Object> result = Maps.newHashMap();
    result.put("workflow", workFlow);
    result.put("task", buildHistory);
    this.setBizResult(context, result);
  }

  /**
   * ??????????????????????????????
   *
   * @param context
   * @throws Exception
   */
  public void doGetFullBuildHistory(Context context) throws Exception {
    Integer wfid = this.getInt("wfid", -1);
    boolean getwf = this.getBoolean("getwf");
    WorkFlow workFlow = new WorkFlow();
    WorkFlowBuildHistoryCriteria query = new WorkFlowBuildHistoryCriteria();
    WorkFlowBuildHistoryCriteria.Criteria criteria = query.createCriteria();
    if (isCollectionAware()) {
      criteria.andAppIdEqualTo(this.getAppDomain().getAppid());
    } else {
      if (wfid < 0) {
        throw new IllegalArgumentException("param wfid can not small than 0");
      }
      criteria.andWorkFlowIdEqualTo(wfid);
      workFlow = new WorkFlow();
      if (getwf) {
        workFlow = this.getWorkflowDAOFacade().getWorkFlowDAO().selectByPrimaryKey(wfid);
        if (workFlow == null) {
          throw new IllegalStateException("can not find workflow in db ,wfid:" + wfid);
        }
      }
    }
    query.setOrderByClause("id desc");

    IWorkFlowBuildHistoryDAO historyDAO = this.getWorkflowDAOFacade().getWorkFlowBuildHistoryDAO();
    Pager pager = this.createPager();
    pager.setTotalCount(historyDAO.countByExample(query));
    this.setBizResult(context
      , new PaginationResult(pager, adapterBuildHistory(historyDAO.selectByExample(query, pager.getCurPage(), pager.getRowsPerPage())), workFlow.getName()));
  }

  private List<ExtendWorkFlowBuildHistory> adapterBuildHistory(List<WorkFlowBuildHistory> histories) {
    return histories.stream().map((r) -> {
      return new ExtendWorkFlowBuildHistory(r);
    }).collect(Collectors.toList());
  }

  /**
   * ??????POJO ??????
   *
   * @param context
   * @throws Exception
   */
  public void doGetPojoData(Context context) throws Exception {
    final AppDomainInfo appDomainInfo = this.getAppDomain();
    try (StringWriter writer = new StringWriter()) {
      if (!ViewPojo.downloadResource(context, appDomainInfo, this, writer)) {
        return;
      }
      this.setBizResult(context, writer.toString());
    }
  }

  /**
   * ????????????????????????
   *
   * @param context
   * @throws Exception
   */
  public void doGetIndexExist(Context context) throws Exception {
    Map<String, Object> biz = Maps.newHashMap();
    biz.put("indexExist", this.isIndexExist());
    biz.put("app", this.getAppDomain().getApp());
    this.setBizResult(context, biz);
  }

  /**
   * Corenodemanage?????????????????????????????????????????????
   *
   * @param context
   * @throws Exception
   */
  public void doGetViewData(Context context) throws Exception {
    Map<String, Object> result = getCollectionStatus(this);
    this.setBizResult(context, result);
  }

  public static Map<String, Object> getCollectionStatus(BasicModule module) throws Exception {
    Map<String, Object> result = new HashMap<>();
    result.put("instanceDirDesc", getInstanceDirDesc(module));
    result.put("topology", getCollectionTopology(module));
    result.put("config", module.getConfigGroup0());
    result.put("app", module.getAppDomain());
    ClusterStateCollectAction.StatusCollectStrategy collectStrategy = ClusterStateCollectAction.getCollectStrategy(
      module.getClusterSnapshotDAO(), module.getAppDomain().getAppid(), ClusterStateCollectAction.TODAY);
    ClusterSnapshot.Summary metricSummary = collectStrategy.getMetricSummary();
    result.put("metrics", metricSummary);
    return result;
  }

  public static CollectionTopology getCollectionTopology(BasicModule module) throws Exception {
    final QueryResutStrategy queryResutStrategy
      = QueryIndexServlet.createQueryResutStrategy(module.getAppDomain(), module.getRequest(), module.getResponse(), module.getDaoContext());
    return queryResutStrategy.createCollectionTopology();
    // //
    // URL url = new URL("http://192.168.28.200:8080/solr/admin/zookeeper?_=1587217613386&detail=true&path=%2Fcollections%2Fsearch4totalpay%2Fstate.json&wt=json");
    // return HttpUtils.get(url, new StreamProcess<CollectionTopology>() {
    // @Override
    // public CollectionTopology p(int status, InputStream stream, Map<String, List<String>> headerFields) {
    // JSONTokener tokener = new JSONTokener(stream);
    // JSONObject result = new JSONObject(tokener);
    //
    // result.getString("data");
    //
    //
    // return null;
    // }
    // });
  }


  public static ServerGroupAdapter getServerGroup0(AppDomainInfo domain, BasicModule module) {
    List<ServerGroupAdapter> groupList = BasicScreen.createServerGroupAdapterList(new BasicScreen.ServerGroupCriteriaSetter() {

      @Override
      public void process(ServerGroupCriteria.Criteria criteria) {
        criteria.andAppIdEqualTo(domain.getAppid()).andRuntEnvironmentEqualTo(domain.getRunEnvironment().getId());
        criteria.andGroupIndexEqualTo((short) 0);
        // if (publishSnapshotIdIsNotNull) {
        criteria.andPublishSnapshotIdIsNotNull();
        // }
      }

      @Override
      public List<Server> getServers(RunContext daoContext, ServerGroup group) {
        return Collections.emptyList();
      }

      @Override
      public int getMaxSnapshotId(ServerGroup group, RunContext daoContext) {
        SnapshotCriteria snapshotCriteria = new SnapshotCriteria();
        snapshotCriteria.createCriteria().andAppidEqualTo(group.getAppId());
        return daoContext.getSnapshotDAO().getMaxSnapshotId(snapshotCriteria);
      }
    }, true, module);
    for (ServerGroupAdapter group : groupList) {
      return group;
    }
    return null;
  }

  /**
   * ??????????????????????????????
   *
   * @return
   * @throws Exception
   */
  private static InstanceDirDesc getInstanceDirDesc(BasicModule module) throws Exception {
    InstanceDirDesc dirDesc = new InstanceDirDesc();
    dirDesc.setValid(false);
    DocCollection collection = module.getIndex();
    final Set<String> instanceDir = new HashSet<String>();
    if (collection.getSlices().isEmpty()) {
      dirDesc.setDesc("??????????????????,?????????????????????");
      return dirDesc;
    }
    boolean hasGetAllCount = false;
    URL url = null;
    for (Slice slice : collection.getSlices()) {
      for (final Replica replica : slice.getReplicas()) {
        if (!hasGetAllCount) {
          dirDesc.setAllcount(getAllRowsCount(collection.getName(), replica.getCoreUrl()));
          hasGetAllCount = true;
        }
        //ZkCoreNodeProps.getCoreUrl
        url = new URL(replica.getCoreUrl() + "admin/mbeans?stats=true&cat=CORE&key=core&wt=xml");
        // http://192.168.28.200:8080/solr/search4supplyGoods2_shard1_replica_n1/admin/mbeans?stats=true&cat=CORE&key=core&wt=xml
        ConfigFileContext.processContent(url, new StreamProcess<Object>() {

          @Override
          @SuppressWarnings("all")
          public Object p(int s, InputStream stream, Map<String, List<String>> headerFields) {
            SimpleOrderedMap result = (SimpleOrderedMap) RESPONSE_PARSER.processResponse(stream, TisUTF8.getName());
            final SimpleOrderedMap mbeans = (SimpleOrderedMap) result.get("solr-mbeans");
            SimpleOrderedMap core = (SimpleOrderedMap) ((SimpleOrderedMap) mbeans.get("CORE")).get("core");
            SimpleOrderedMap status = ((SimpleOrderedMap) core.get("stats"));
            // core ???????????????status??????
            if (status == null) {
              return null;
            }
            String indexDir = StringUtils.substringAfterLast((String) status.get("CORE.indexDir"), "/");
            instanceDir.add(indexDir);
            ReplicState replicState = new ReplicState();
            replicState.setIndexDir(indexDir);
            replicState.setValid(StringUtils.isNotBlank(indexDir));
            if (StringUtils.isBlank(indexDir)) {
              replicState.setInvalidDesc("?????????????????????");
            }
            replicState.setNodeName(StringUtils.substringBefore(replica.getNodeName(), ":"));
            ;
            replicState.setCoreName(StringUtils.substringAfter((String) core.get("class"), "_"));
            replicState.setState(replica.getState().toString());
            replicState.setLeader(replica.getBool("leader", false));
            dirDesc.addReplicState(slice.getName(), replicState);
            return null;
          }
        });
      }
    }
    final StringBuffer replicDirDesc = new StringBuffer();
    if (instanceDir.size() > 1) {
      replicDirDesc.append("(");
      int count = 0;
      for (String d : instanceDir) {
        replicDirDesc.append(d);
        if (++count < instanceDir.size()) {
          replicDirDesc.append(",");
        }
      }
      replicDirDesc.append(")");
      dirDesc.setDesc("?????????????????????????????????:" + replicDirDesc);
    } else if (instanceDir.size() == 1) {
      if (dirDesc.isAllReplicValid()) {
        dirDesc.setValid(true);
        for (String d : instanceDir) {
          dirDesc.setDesc("?????????????????????:" + d);
          break;
        }
      } else {
        dirDesc.setValid(false);
        dirDesc.setDesc(dirDesc.invalidDesc().toString());
      }
    } else {
      dirDesc.setDesc("?????????????????????,size:" + instanceDir.size());
    }
    return dirDesc;
  }

  public static long getAllRowsCount(String collectionName, String coreURL) throws SolrServerException, IOException {
    QueryCloudSolrClient solrClient = new QueryCloudSolrClient(coreURL);
    SolrQuery query = new SolrQuery();
    query.setRows(0);
    query.setQuery("*:*");
    return solrClient.query(collectionName, query).getResults().getNumFound();
  }

  /**
   * ??????????????????
   *
   * @param context
   * @throws Exception
   */
  @Func(value = PermissionConstant.PERMISSION_INCR_PROCESS_MANAGE)
  public void doIncrDelete(Context context) throws Exception {

    IndexStreamCodeGenerator indexStreamCodeGenerator = getIndexStreamCodeGenerator(this);
    indexStreamCodeGenerator.deleteScript();

    TISK8sDelegate k8sDelegate = TISK8sDelegate.getK8SDelegate(this.getCollectionName());
    // ??????????????????
    k8sDelegate.removeIncrProcess();
  }

//  /**
//   * ???????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
//   *
//   * @param context
//   * @throws Exception
//   */
//  @Func(value = PermissionConstant.PERMISSION_INCR_PROCESS_MANAGE)
//  public void doIncrResumePause(Context context) throws Exception {
//    boolean pause = this.getBoolean("pause");
//    final String collection = this.getAppDomain().getAppName();
//
//    JobType.RemoteCallResult<?> callResult = JobType.IndexJobRunning.assembIncrControl(
//      getAssembleNodeAddress(this.getSolrZkClient()), collection, Lists.newArrayList(new PostParam("stop", pause)), null);
//    if (!callResult.success) {
//      this.addErrorMessage(context, callResult.msg);
//      return;
//    }
//    this.addActionMessage(context, collection + ":????????????????????????:" + (pause ? "??????" : "??????"));
//  }

  //

  /**
   * ??????????????????,?????????????????????????????
   */
  @Func(PermissionConstant.APP_INITIALIZATION)
  public void doCreateCoreSetp1(Context context) throws Exception {
    final Integer groupNum = this.getInt("group");
    final Integer repliation = this.getInt("replica");
    if (groupNum == null || groupNum < 1) {
      this.addErrorMessage(context, "??????????????????1");
      return;
    }
    if (repliation == null || repliation < 1) {
      this.addErrorMessage(context, "???????????????????????????1");
      return;
    }
    context.put(CREATE_CORE_SELECT_COREINFO, new CreateCorePageDTO(groupNum, repliation, "y".equals(this.getString("exclusive"))));
    // this.forward("coredefine.vm");
  }

  public static class CreateCorePageDTO {

    private final int groupNum;

    private final int replication;

    private final int assignGroupCount;

    private final boolean excludeHaveAppServer;

    /**
     * @param groupNum
     * @param replication
     * @param excludeHaveAppServers ?????????????????????????????????????????????
     */
    public CreateCorePageDTO(int groupNum, int replication, boolean excludeHaveAppServers) {
      this(0, groupNum, replication, excludeHaveAppServers);
    }

    public CreateCorePageDTO(int assignGroupCount, int groupNum, int replication, boolean excludeHaveAppServers) {
      super();
      this.groupNum = groupNum;
      this.replication = replication;
      this.assignGroupCount = assignGroupCount;
      // ?????????????????????????????????
      this.excludeHaveAppServer = excludeHaveAppServers;
    }

    public int getAssignGroupCount() {
      return assignGroupCount;
    }

    public int getGroupNum() {
      return groupNum;
    }

    public int getReplication() {
      return replication;
    }

    public boolean isExcludeHaveAppServers() {
      return excludeHaveAppServer;
    }

    public int[] getGroupArray() {
      int[] result = new int[groupNum];
      for (int i = 0; i < groupNum; i++) {
        result[i] = assignGroupCount + i;
      }
      return result;
    }
  }

  /**
   * ??????????????????
   */
  @SuppressWarnings("all")
  @Func(PermissionConstant.APP_INITIALIZATION)
  public void doCreateCore(final Context context) throws Exception {
    final Integer groupNum = this.getInt("group");
    // ???????????????????????? ?????????
    final Integer repliation = this.getInt("replica");
    if (groupNum == null || groupNum < 1) {
      this.addErrorMessage(context, "??????????????????1");
      return;
    }
    if (repliation == null || repliation < 1) {
      this.addErrorMessage(context, "???????????????????????????1");
      return;
    }
    FCoreRequest request = createCoreRequest(context, groupNum, repliation, "??????", true);
    this.createCollection(this, context, groupNum, repliation, request, this.getServerGroup0().getPublishSnapshotId());
  }

  /**
   * ??????????????????
   *
   * @param module
   * @param context
   * @throws Exception
   */
  public static void deleteCollection(BasicModule module, final Context context) throws Exception {

//    URL url = new URL("http://" + getCloudOverseerNode(module.getSolrZkClient()) + ADMIN_COLLECTION_PATH
//      + "?action=DELETE&name" + module.getCollectionName());
//    log.info("delete collection in  cloud url:" + url);
//    HttpUtils.processContent(url, new StreamProcess<Object>() {
//
//      @Override
//      public Object p(int status, InputStream stream, Map<String, List<String>> headerFields) {
//        ProcessResponse result = null;
//        if (!(result = ProcessResponse.processResponse(stream, (err) -> module.addErrorMessage(context, err))).success) {
//          throw new IllegalStateException("collection:" + module.getCollectionName() + " has not been delete");
//        }
//        return null;
//      }
//    });
  }

  /**
   * ?????????????????? ????????????
   *
   * @param module
   * @param context
   * @return
   */
  public static List<String> listCollection(BasicModule module, final Context context) throws Exception {
    //final String cloudOverseer = getCloudOverseerNode(module.getSolrZkClient());
    // : "plain";
    List<String> collections = Lists.newArrayList();
//    URL url = new URL("http://" + cloudOverseer + ADMIN_COLLECTION_PATH + "?action=LIST");
//    log.info("list collection in  cloud url:" + url);
//    HttpUtils.processContent(url, new StreamProcess<Object>() {
//
//      @Override
//      public Object p(int status, InputStream stream, Map<String, List<String>> headerFields) {
//        ProcessResponse result = null;
//        if ((result = ProcessResponse.processResponse(stream, (err) -> module.addErrorMessage(context, err))).success) {
//          Map<String, Object> biz = (Map<String, Object>) result.result;
//          List<String> clist = (List<String>) biz.get("collections");
//          if (!CollectionUtils.isEmpty(clist)) {
//            collections.addAll(clist);
//          }
//        }
//        return null;
//      }
//    });
    return collections;
  }

  /**
   * ??????????????????
   *
   * @param module
   * @param context
   * @param groupNum
   * @param repliationCount
   * @param request
   * @param publishSnapshotId
   * @throws Exception
   * @throws MalformedURLException
   * @throws UnsupportedEncodingException
   */
  public static boolean createCollection(BasicModule module, final Context context, final Integer groupNum
    , final Integer repliationCount, FCoreRequest request, int publishSnapshotId) throws Exception {
//    if (publishSnapshotId < 0) {
//      throw new IllegalArgumentException("shall set publishSnapshotId");
//    }
//    if (!request.isValid()) {
//      return false;
//    }
//
//    SnapshotDomain snapshotDomain = module.getSnapshotViewDAO().getView(publishSnapshotId);
//    InputStream input = null;
//    IIndexMetaData meta = SolrFieldsParser.parse(() -> snapshotDomain.getSolrSchema().getContent());
//    ParseResult parseResult = meta.getSchemaParseResult();
//    String routerField = parseResult.getSharedKey();
//    if (StringUtils.isBlank(routerField)) {
//      module.addErrorMessage(context, "Schema?????????????????????sharedKey???");
//      return false;
//    }
//    final String cloudOverseer = getCloudOverseerNode(module.getSolrZkClient());
//    String tisRepositoryHost = StringUtils.EMPTY;
//    if (Config.isTestMock()) {
//      tisRepositoryHost = "&" + PropteryGetter.KEY_PROP_TIS_REPOSITORY_HOST + "=" + URLEncoder.encode(ManageUtils.getServerIp(), getEncode());
//    }
//
//    final String routerName = HashcodeRouter.NAME;
//    URL url = new URL("http://" + cloudOverseer + CREATE_COLLECTION_PATH
//      + request.getIndexName() + "&router.name=" + routerName + "&router.field=" + routerField
//      + "&replicationFactor=" + repliationCount + "&numShards=" + groupNum
//      + "&collection.configName=" + DEFAULT_SOLR_CONFIG + "&maxShardsPerNode=" + MAX_SHARDS_PER_NODE
//      + "&property.dataDir=data&createNodeSet=" + URLEncoder.encode(request.getCreateNodeSet(), getEncode())
//      + "&" + PropteryGetter.KEY_PROP_CONFIG_SNAPSHOTID + "=" + publishSnapshotId
//      + tisRepositoryHost);
//
//    log.info("create new cloud url:" + url);
//    HttpUtils.processContent(url, new StreamProcess<Object>() {
//
//      @Override
//      public Object p(int status, InputStream stream, Map<String, List<String>> headerFields) {
//        ProcessResponse result = null;
//        if ((result = ProcessResponse.processResponse(stream, (err) -> module.addErrorMessage(context, err))).success) {
//          module.addActionMessage(context, "?????????????????????????????????" + groupNum + "???,??????" + repliationCount + "?????????");
//        }
//        return null;
//      }
//    });
    return true;
  }

  public static String getCloudOverseerNode(ITISCoordinator zkClient) {
    Map v = JSON.parseObject(zkClient.getData(ZkUtils.ZK_PATH_OVERSEER_ELECT_LEADER, null, new Stat(), true), Map.class, Feature.AllowUnQuotedFieldNames);
    String id = (String) v.get("id");
    if (id == null) {
      throw new IllegalStateException("collection cluster overseer node has not launch");
    }
    String[] arr = StringUtils.split(id, "-");
    if (arr.length < 3) {
      throw new IllegalStateException("overseer ephemeral node id:" + id + " is illegal");
    }
    return StringUtils.substringBefore(arr[1], "_");
  }

  /**
   * ????????????schema
   *
   * @param context
   * @throws Exception
   */
  @Func(PermissionConstant.APP_SCHEMA_UPDATE)
  public void doUpdateSchemaAllServer(final Context context) throws Exception {
    final boolean needReload = this.getBoolean("needReload");
    Integer snapshotId = this.getInt(ICoreAdminAction.TARGET_SNAPSHOT_ID);
    DocCollection docCollection = TISZkStateReader.getCollectionLive(this.getZkStateReader(), this.getCollectionName());
    pushConfig2Engine(this, context, docCollection, snapshotId, needReload);
  }

  public static void pushConfig2Engine(BasicModule module, Context context, DocCollection collection, int snapshotId, boolean needReload) throws Exception {
    // module.errorsPageShow(context);
    if (traverseCollectionReplic(collection, false, /* collection */
      new ReplicaCallback() {

        @Override
        public boolean process(boolean isLeader, final Replica replica) throws Exception {
          try {
            URL url = new URL(replica.getStr(BASE_URL_PROP) + "/admin/cores?action=CREATEALIAS&" + ICoreAdminAction.EXEC_ACTION + "=" + ICoreAdminAction.ACTION_UPDATE_CONFIG + "&core=" + replica.getStr(CORE_NAME_PROP) + "&" + ZkStateReader.COLLECTION_PROP + "=" + collection.getName() + "&needReload=" + needReload + "&" + ICoreAdminAction.TARGET_SNAPSHOT_ID + "=" + snapshotId);
            return HttpUtils.processContent(url, new StreamProcess<Boolean>() {

              @Override
              public Boolean p(int status, InputStream stream, Map<String, List<String>> headerFields) {
                ProcessResponse result = null;
                if ((result = ProcessResponse.processResponse(stream, (err) -> module.addErrorMessage(context, replica.getName() + "," + err))).success) {
                  // addActionMessage(context, "?????????????????????????????????" + groupNum + "???,??????" + repliationCount + "?????????");
                  // return true;
                }
                return true;
              }
            });
          } catch (MalformedURLException e) {
            // e.printStackTrace();
            log.error(collection.getName(), e);
            module.addErrorMessage(context, e.getMessage());
          }
          return true;
        }
      })) {
      // module.addActionMessage(context, "???????????????????????????????????????");
    }
  }

  /**
   * ???????????????????????????
   *
   * @param collection
   * @param firstProcessLeader
   * @param replicaCallback
   * @return
   * @throws Exception
   */
  public static boolean traverseCollectionReplic(final DocCollection collection, boolean firstProcessLeader, ReplicaCallback replicaCallback) throws Exception {
    if (collection == null) {
      throw new IllegalStateException("param collection can not be null");
    }
    Replica leader = null;
    // String requestId = null;
    for (Slice slice : collection.getSlices()) {
      leader = slice.getLeader();
      if (firstProcessLeader) {
        if (!replicaCallback.process(true, leader)) {
          return false;
        }
      }
      for (Replica replica : slice.getReplicas()) {
        if (leader == replica) {
          continue;
        }
        if (!replicaCallback.process(true, replica)) {
          return false;
        }
      }
      if (!firstProcessLeader) {
        if (!replicaCallback.process(true, leader)) {
          return false;
        }
      }
    }
    return true;
  }

  public static interface ReplicaCallback {
    public boolean process(boolean isLeader, Replica replica) throws Exception;
  }


  private Map<String, CoreNode> getCoreNodeMap(boolean isAppNameAware) {
    CoreNode[] nodelist = SelectableServer.getCoreNodeInfo(this.getRequest(), this, false, isAppNameAware);
    Map<String, CoreNode> result = new HashMap<String, CoreNode>();
    for (CoreNode node : nodelist) {
      result.put(node.getNodeName(), node);
    }
    return result;
  }

  //
  // /**
  // * @param context
  // * @param serverSuffix
  // * @param isAppNameAware
  // * @param mustSelectMoreOneReplicAtLeast
  // * ??????????????????????????????????????????core?????????????????????????????????????????????????????? <br/>
  // * ??????????????????????????????????????????????????????????????????
  // * @return
  // */
  private ParseIpResult parseIps(Context context, String serverSuffix, boolean isAppNameAware, boolean mustSelectMoreOneReplicAtLeast) {
    ParseIpResult result = new ParseIpResult();
    result.valid = false;
    List<String> parseips = new ArrayList<String>();
    String[] ips = this.getRequest().getParameterValues("selectedServer" + StringUtils.trimToEmpty(serverSuffix));
    if (ips == null) {
      return result;
    }
    if (mustSelectMoreOneReplicAtLeast && ips.length < 1) {
      addErrorMessage(context, "???" + (StringUtils.isNotEmpty(serverSuffix) ? "??????" + serverSuffix + "???" : StringUtils.EMPTY) + "???????????????");
      return result;
    }
    // StringBuffer ipLiteria = new StringBuffer();
    result.ipLiteria.append("[");
    Matcher matcher = null;
    final Map<String, CoreNode> serverdetailMap = getCoreNodeMap(isAppNameAware);
    CoreNode nodeinfo = null;
    CoreNode current = null;
    for (String ip : ips) {
      matcher = this.isValidIpPattern(ip);
      if (!matcher.matches()) {
        this.addErrorMessage(context, "IP:" + ip + "?????????????????????");
        return result;
      }
      // ????????? ?????????????????????lucene??????????????????
      current = serverdetailMap.get(ip);
      if (current == null) {
        this.addErrorMessage(context, "?????????" + ip + "???????????????????????????");
        return result;
      }
      if (nodeinfo != null) {
        if (current.getLuceneSpecVersion() != nodeinfo.getLuceneSpecVersion()) {
          this.addErrorMessage(context, (StringUtils.isNotEmpty(serverSuffix) ? "???" + serverSuffix + "???" : StringUtils.EMPTY) + "?????????Lucene???????????????");
          return result;
        }
      }
      nodeinfo = current;
      // ????????? ?????????????????????lucene??????????????????
      // matcher.group(group)
      parseips.add(matcher.group(0));
      result.ipLiteria.append(ip).append(",");
    }
    result.ipLiteria.append("]");
    result.ips = parseips.toArray(new String[parseips.size()]);
    result.valid = true;
    return result;
  }

  //
  private static class ParseIpResult {

    private boolean valid;

    private String[] ips;

    private final StringBuffer ipLiteria = new StringBuffer();
  }

  /**
   * @param context
   * @param appName
   * @param ips     ?????????ip??????
   * @return
   */
  public static CoreRequest createIps(Context context, final String appName, String[] ips) {
    CoreRequest request = new CoreRequest();
    request.setIncludeIps(ips);
    request.setServiceName(appName);
    request.runtime = RunEnvironment.getSysRuntime();
    log.debug("request.getRunEnv():" + request.getRunEnv());
    return request;
  }

  public static class CoreRequest {

    private String[] ips;

    private String serviceName;

    // private boolean monopolized;
    private RunEnvironment runtime;

    public void addNodeIps(String groupIndex, String ip) {
    }

    public void setIncludeIps(String[] ips) {
      this.ips = ips;
    }

    public void setServiceName(String serviceName) {
      this.serviceName = serviceName;
    }

    public String getServiceName() {
      return serviceName;
    }

    // public void setMonopolized(boolean monopolized) {
    // this.monopolized = monopolized;
    // }
    public RunEnvironment getRunEnv() {
      return this.runtime;
    }
  }

  private abstract static class AppendParams {

    abstract List<HttpUtils.PostParam> getParam();
  }

  /**
   * ????????????
   *
   * @param context
   * @throws Exception
   */
  @Func(PermissionConstant.APP_TRIGGER_FULL_DUMP)
  public void doTriggerSynIndexFile(Context context) throws Exception {
    final String userpoint = this.getString("userpoint");
    if (StringUtils.isBlank(userpoint)) {
      this.addErrorMessage(context, "?????????userpoint????????????admin#yyyyMMddHHmmss");
      return;
    }
    boolean success = triggerBuild("indexBackflow", context, userpoint);
    if (success) {
      this.addActionMessage(context, "???????????????userpoint??????" + StringUtils.defaultIfEmpty(userpoint, "-1"));
    }
  }

  /**
   * @param context
   * @param userpoint
   * @return
   * @throws Exception
   */
  private boolean triggerBuild(final String startPhrase, Context context, final String userpoint) throws Exception {
    String ps = null;
    String user = null;
    if (StringUtils.indexOf(userpoint, "#") > -1) {
      ps = StringUtils.substringAfter(userpoint, "#");
      user = StringUtils.substringBefore(userpoint, "#");
    } else {
      ps = userpoint;
    }
    // TODO ??????????????????????????????
    final String pps = ps;
    final String fuser = user;
    boolean success = sendRequest2FullIndexSwapeNode(this, context, new AppendParams() {

      @Override
      List<PostParam> getParam() {
        List<PostParam> params = Lists.newArrayList();
        params.add(new PostParam(IParamContext.COMPONENT_START, startPhrase));
        params.add(new PostParam(IParamContext.COMPONENT_START, pps));
        // "&ps=" + ps);
        if (StringUtils.isNotBlank(fuser)) {
          // param.append("&user=").append(fuser);
          params.add(new PostParam("user", fuser));
        }
        return params;
      }
    }).success;
    return success;
  }

  /**
   * ????????????build????????????dump???
   *
   * @param context
   * @throws Exception
   */
  @Func(PermissionConstant.APP_TRIGGER_FULL_DUMP)
  public void doTriggerFullDumpFile(Context context) throws Exception {
    final String userpoint = this.getString("userpoint");
    if (StringUtils.isBlank(userpoint)) {
      this.addErrorMessage(context, "?????????userpoint????????????admin#yyyyMMddHHmmss");
      return;
    }
    boolean success = triggerBuild("indexBuild", context, userpoint);
    if (success) {
      addActionMessage(context, "???????????????triggerFullDumpFile,userpoint:" + StringUtils.defaultIfEmpty(userpoint, "-1"));
    }
  }

  //
  // // private TriggerJobConsole triggerJobConsole;
  //
  // // @Autowired
  // // public final void setTriggerJobConsole(TriggerJobConsole
  // // triggerJobConsole) {
  // // this.triggerJobConsole = triggerJobConsole;
  // // }
  //
  // /**
  // * ??????dump??????????????????
  // *
  // * @return
  // */
  // private boolean isDumpWorking(Context context) {
  // // try {
  // //
  // // String coreName = this.getAppDomain().getAppName();
  // //
  // // boolean pause = triggerJobConsole.isPause(coreName);
  // //
  // // if (!pause) {
  // // this.addErrorMessage(context, "???????????????" + coreName
  // // + "???Dump Job ?????????????????????????????????????????????????????????");
  // // }
  // //
  // // return !pause;
  // //
  // // } catch (RemoteException e) {
  // // throw new RuntimeException(e);
  // // }
  // return false;
  // }
  //
  // private String getServiceName() {
  // return this.getAppDomain().getAppName();
  // }
  //
  // /**
  // * ??????????????????schema??????
  // *
  // * @param context
  // * @throws Exception
  // */
  // @Func(PermissionConstant.APP_SCHEMA_UPDATE)
  // public void doUpdateSchemaByGroup(Context context) throws Exception {
  //
  // Integer group = this.getInt("group");
  // // Assert.assertNotNull(group);
  // if (group == null || group < 0) {
  // this.addErrorMessage(context, "????????????");
  // return;
  // }
  //
  // if (isDumpWorking(context)) {
  // return;
  // }
  //
  // this.getClientProtocol().schemaChange(getServiceName(), group);
  // this.addActionMessage(context, "?????????" + group + "???Schema??????????????????");
  // }
  //
  // /**
  // * ???????????????solrconfig
  // *
  // * @param context
  // * @throws Exception
  // */
  // @Func(PermissionConstant.APP_SOLRCONFIG_UPDATE)
  // public void doUpdateSolrconfigAllServer(Context context) throws Exception
  // {
  // if (isDumpWorking(context)) {
  // return;
  // }
  // this.getClientProtocol().coreConfigChange(this.getServiceName());
  //
  // this.addActionMessage(context, "?????????????????????????????????Solrconfig");
  // }
  //
  // /**
  // * ??????solrconfig
  // *
  // * @param context
  // * @throws Exception
  // */
  // // doUpdateSchemaByGroup
  // @Func(PermissionConstant.APP_SOLRCONFIG_UPDATE)
  // public void doUpdateSolrconfigByGroup(Context context) throws Exception {
  //
  // Integer group = this.getInt("group");
  // if (group == null || group < 0) {
  // this.addErrorMessage(context, "????????????");
  // return;
  // }
  //
  // if (isDumpWorking(context)) {
  // return;
  // }
  //
  // this.getClientProtocol().coreConfigChange(this.getServiceName(), group);
  // this.addActionMessage(context, "?????????" + group + "???solrconfig??????????????????");
  // }
  //
  // private static final Pattern serverPattern = Pattern
  // .compile("(.+?)_(\\d+)");
  //
  // /**
  // * ??????????????????????????????
  // *
  // * @param context
  // * @throws Exception
  // */
  // @Func(PermissionConstant.APP_SOLRCONFIG_UPDATE)
  // public void doUpdateSolrConfigByServer(Context context) throws Exception
  // {
  // // Integer group = this.getInt("group");
  // // Assert.assertNotNull(group);
  // //
  // // String server = this.getString("server");
  // // Assert.assertNotNull(group);
  //
  // if (isDumpWorking(context)) {
  // return;
  // }
  //
  // updateResourceByServer(context, new ResourceConfigRefesh() {
  //
  // @Override
  // public void update(String ip, Integer group, Context context)
  // throws Exception {
  // getClientProtocol().coreConfigChange(getServiceName(), group,
  // ip);
  // addActionMessage(context, "???????????????????????????" + ip + ",???" + group
  // + " ?????? solrconfig??????");
  // }
  //
  // });
  //
  // }
  //
  // private void updateResourceByServer(Context context,
  // ResourceConfigRefesh resourceRefesh) throws Exception {
  // String[] servers = this.getRequest().getParameterValues("updateServer");
  //
  // if (servers == null || servers.length < 1) {
  // this.addErrorMessage(context, "??????????????????");
  // return;
  // }
  // Matcher matcher = null;
  // String ip = null;
  // Integer group = null;
  // for (String server : servers) {
  // matcher = serverPattern.matcher(server);
  //
  // if (!matcher.matches()) {
  // this.addErrorMessage(context, "????????????updateServer ????????????" + server);
  // return;
  // }
  //
  // group = Integer.parseInt(matcher.group(2));
  //
  // matcher = this.isValidIpPattern(matcher.group(1));
  // if (!matcher.matches()) {
  // this.addErrorMessage(context, "IP:" + ip + "?????????????????????");
  // return;
  // }
  //
  // ip = matcher.group(1);
  //
  // resourceRefesh.update(ip, group, context);
  //
  // }
  // }
  //
  // private interface ResourceConfigRefesh {
  // public void update(String ip, Integer group, Context context)
  // throws Exception;
  //
  // }
  //
  // // do_update_hsf_all_server
  // @Func(PermissionConstant.APP_HSF_UPDATE)
  // public void doUpdateHsfAllServer(Context context) throws Exception {
  //
  // this.getClientProtocol().rePublishHsf(this.getServiceName());
  //
  // this.addActionMessage(context, "???????????????????????????hsf????????????");
  // }
  //
  // // doUpdateSolrconfigByGroup
  // @Func(PermissionConstant.APP_HSF_UPDATE)
  // public void doUpdateHsfByGroup(Context context) throws Exception {
  //
  // Integer group = this.getInt("group");
  // if (group == null || group < 0) {
  // this.addErrorMessage(context, "???????????????");
  // return;
  // }
  //
  // this.getClientProtocol().rePublishHsf(this.getServiceName(), group);
  // this.addActionMessage(context, "?????????" + group + "???Hsf??????????????????");
  // }
  //
  // /**
  // * @param context
  // * @throws Exception
  // */
  // @Func(PermissionConstant.APP_HSF_UPDATE)
  // public void doUpdateHsfByServer(Context context) throws Exception {
  // updateResourceByServer(context, new ResourceConfigRefesh() {
  // @Override
  // public void update(String ip, Integer group, Context context)
  // throws Exception {
  // getClientProtocol().republishHsf(getServiceName(), group, ip);
  // addActionMessage(context, "?????????????????????[" + ip + "]HSF??????????????????");
  // }
  // });
  // // Integer group = this.getInt("group");
  // // // Assert.assertNotNull(group);
  // // if (group == null || group < 1) {
  // // this.addErrorMessage(context, "???????????????");
  // // return;
  // // }
  // //
  // // final String server = this.getString("server");
  // // // Matcher m = PATTERN_IP.matcher(StringUtils.trimToEmpty(server));
  // // if (isValidIpPattern(server)) {
  // // this.addErrorMessage(context, "???????????????");
  // // return;
  // // }
  // //
  // // this.getClientProtocol().republishHsf(this.getServiceName(), group,
  // // server);
  // //
  // // this.addActionMessage(context, "?????????????????????[" + server + "]HSF??????????????????");
  //
  // }
  //
  // /**
  // *
  // * @return
  // */
  private Matcher isValidIpPattern(String server) {
    return PATTERN_IP.matcher(StringUtils.trimToEmpty(server));
    // return (m.matches());
  }

  //
  // /**
  // * ????????????????????????
  // *
  // * @param context
  // * @throws Exception
  // */
  // @Func(PermissionConstant.APP_REPLICA_MANAGE)
  // public void doDecreaseReplica(Context context) throws Exception {
  //
  // if (isDumpWorking(context)) {
  // return;
  // }
  //
  // // this.getClientProtocol().desCoreReplication(request, replication);
  // updateReplica(context, new ReplicaUpdate() {
  // @Override
  // public String getExecuteLiteria() {
  // return "??????";
  // }
  //
  // @Override
  // public void update(CoreRequest request, short[] replicCount)
  // throws IOException {
  // getClientProtocol().desCoreReplication(request, replicCount);
  // }
  //
  // // @Override
  // // public void update(CoreRequest corerequest, Integer replica)
  // // throws IOException {
  // //
  // // }
  //
  // @Override
  // public boolean shallContinueProcess(Context context,
  // FCoreRequest request) {
  // // ???????????????????????????master???????????????????????????
  // boolean canReduceReplica = !getClientProtocol()
  // .hasRealTimeLeaderServer(
  // CoreAction.this.getServiceName(),
  // request.getIps());
  // if (!canReduceReplica) {
  // CoreAction.this.addErrorMessage(context, "????????????????????????"
  // + request.getIps()
  // + "?????????????????????master?????????master?????????????????????");
  // }
  //
  // return canReduceReplica;
  // }
  //
  // });
  // }
  //
  // /**
  // * ????????????????????????
  // *
  // * @param context
  // * @throws Exception
  // */
  // @Func(PermissionConstant.APP_REPLICA_MANAGE)
  // public void doAddReplica(Context context) throws Exception {
  //
  // if (isDumpWorking(context)) {
  // return;
  // }
  //
  // updateReplica(context, new ReplicaUpdate() {
  // @Override
  // public String getExecuteLiteria() {
  // return "??????";
  // }
  //
  // @Override
  // public void update(CoreRequest request, short[] replicCount)
  // throws IOException {
  // getClientProtocol().addCoreReplication(request, replicCount);
  // }
  // });
  // }
  //
  // /**
  // * ??????????????????????????????????????????????????????
  // *
  // * @param context
  // * @param replicaUpdate
  // * @throws Exception
  // */
  // private void updateReplica(Context context, ReplicaUpdate replicaUpdate)
  // throws Exception {
  // final Integer replica = this.getInt("replica");
  // Integer group = this.getInt("groupcount");
  //
  // if (replica == null || replica < 1) {
  // this.addErrorMessage(context, "?????????????????????????????????");
  // return;
  // }
  // if (group == null || group < 1) {
  // this.addErrorMessage(context, "???????????????");
  // return;
  // }
  //
  // if (!this.getCoreManager().isCreateNewServiceSuc(this.getServiceName()))
  // {
  // this.addErrorMessage(context, "???Solr??????????????????????????????????????????????????????????????????");
  // return;
  // }
  //
  // FCoreRequest request = createCoreRequest(context, 0, group, replica,
  // replicaUpdate.getExecuteLiteria(),/* isAppNameAware */true, /*
  // mustSelectMoreOneReplicAtLeast */
  // false);
  //
  // // FCoreRequest request = createCoreRequest(context, group, replica,
  // // replicaUpdate.getExecuteLiteria(), false/*
  // // mustSelectMoreOneReplicAtLeast */);
  //
  // if (!request.isValid()
  // && replicaUpdate.shallContinueProcess(context, request)) {
  // return;
  // }
  //
  // // ??????????????????
  // // setOperationLogDesc(request);
  //
  // replicaUpdate.update(request.getRequest(), request.getReplicCount());
  // this.addActionMessage(context, replicaUpdate.getExecuteLiteria()
  // + "????????????!");
  // }
  //
  private FCoreRequest createCoreRequest(Context context, Integer group, Integer replica, String setVerbs, boolean mustSelectMoreOneReplicAtLeast) {
    return createCoreRequest(context, 0, group, replica, setVerbs, mustSelectMoreOneReplicAtLeast);
  }

  private FCoreRequest createCoreRequest(Context context, int assignGroupCount, int group, int replica, String setVerbs, boolean mustSelectMoreOneReplicAtLeast) {
    return createCoreRequest(context, assignGroupCount, group, replica, setVerbs, /* isAppNameAware */
      false, mustSelectMoreOneReplicAtLeast);
  }

  /**
   * @param context
   * @param assignGroupCount ??????????????????group??????
   * @param group            ???????????????
   * @param replica
   * @param setVerbs
   * @return
   */
  private FCoreRequest createCoreRequest(Context context, int assignGroupCount, int group, int replica, String setVerbs, boolean isAppNameAware, boolean mustSelectMoreOneReplicAtLeast) {
    // boolean valid = false;
    // CoreRequest request = ;
    CoreRequest coreRequest = createIps(context, this.getCollectionName(), null);
    FCoreRequest result = new FCoreRequest(coreRequest, assignGroupCount + group, assignGroupCount);
    // StringBuffer addserverSummary = new StringBuffer();
    // ???????????????????????????????????????ip??????
    final int GROUP_SIZE = 1;
    for (int i = 0; i < GROUP_SIZE; i++) {
      ParseIpResult parseResult = parseIps(context, String.valueOf(assignGroupCount + i), isAppNameAware, mustSelectMoreOneReplicAtLeast);
      if (parseResult.valid && parseResult.ips.length > 0) {
        if ((parseResult.ips.length * MAX_SHARDS_PER_NODE) < (group * replica)) {
          this.addErrorMessage(context, "??????????????????????????????,?????????" + ((group * replica) / MAX_SHARDS_PER_NODE) + "?????????");
          // if (replica < parseResult.ips.length) {
          // this.addErrorMessage(context, "???" + (assignGroupCount +
          // i)
          // + "??????" + setVerbs + "?????????????????????" + replica + "???");
        } else {
          // for (int j = 0; j < replica; j++) {
          for (int j = 0; j < parseResult.ips.length; j++) {
            result.addNodeIps((assignGroupCount + i), parseResult.ips[j]);
          }
          // addserverSummary.append()
          // this.addActionMessage(context, "???" + (assignGroupCount +
          // i)
          // + "??????" + setVerbs + "????????????" + parseResult.ipLiteria);
          this.addActionMessage(context, "??????????????????:" + parseResult.ipLiteria);
          result.setValid(true);
        }
      }
    }
    if (!result.isValid()) {
      this.addErrorMessage(context, "??????????????????????????????????????????????????????");
    }
    return result;
  }

  // private static abstract class ReplicaUpdate {
  // /**
  // * @param request
  // * @param replicCount
  // * ???????????????????????????
  // * @throws IOException
  // */
  // public abstract void update(CoreRequest request, short[] replicCount)
  // throws IOException;
  //
  // public abstract String getExecuteLiteria();
  //
  // public boolean shallContinueProcess(Context context, FCoreRequest
  // request) {
  // return true;
  // }
  //
  // }
  //
  // /**
  // * ??????????????????
  // *
  // * @param context
  // * @throws Exception
  // */
  // @Func(PermissionConstant.APP_REPLICA_MANAGE)
  // public void doUpdateGroupCountSetp1(Context context) throws Exception {
  // Integer groupcount = this.getInt("addgroup");
  // if (groupcount == null || groupcount < 1) {
  // this.addErrorMessage(context, "??????????????????Group???");
  // return;
  // }
  // if (isDumpWorking(context)) {
  // return;
  // }
  //
  // // ???????????????????????????
  // if (isRealTimeModel(context)) {
  // return;
  // }
  //
  // this.forward("addgroup");
  //
  // LocatedCores locateCore = this.getClientProtocol().getCoreLocations(
  // this.getAppDomain().getAppName());
  //
  // context.put("assignGroupCount", locateCore.getCores().size());
  // int serverSum = 0;
  // for (LocatedCore localtedCore : locateCore.getCores()) {
  // serverSum += localtedCore.getCoreNodeInfo().length;
  // }
  // int replica = 0;
  // try {
  // replica = (serverSum / locateCore.getCores().size());
  // } catch (Throwable e) {
  //
  // }
  //
  // if (replica < 1) {
  // this.addErrorMessage(context, "?????????????????????????????????????????????1");
  // return;
  // }
  //
  // context.put("assignreplica", replica);
  //
  // context.put(CoreAction.CREATE_CORE_SELECT_COREINFO,
  // new CreateCorePageDTO(locateCore.getCores().size(), groupcount,
  // replica, false// locateCore.isMonopy()
  // ));
  //
  // }
  //
  // /**
  // * ??????????????????
  // *
  // * @param context
  // * @throws Exception
  // */
  // @Func(PermissionConstant.APP_REPLICA_MANAGE)
  // public void doUpdateGroupCount(Context context) throws Exception {
  // Integer groupcount = this.getInt("group");
  // if (groupcount == null || groupcount < 1) {
  // this.addErrorMessage(context, "??????????????????Group???");
  // return;
  // }
  //
  // Integer assignGroupCount = this.getInt("assigngroup");
  //
  // if (assignGroupCount == null) {
  // throw new IllegalArgumentException(
  // "assignGroupCount can not be null");
  // }
  //
  // final Integer replica = this.getInt("replica");
  //
  // if (replica == null) {
  // throw new IllegalArgumentException("replica can not be null");
  // }
  //
  // if (isRealTimeModel(context)) {
  // return;
  // }
  //
  // if (isDumpWorking(context)) {
  // return;
  // }
  //
  // FCoreRequest result = createCoreRequest(context, assignGroupCount,
  // groupcount, replica, "??????",/* isAppNameAware */true);
  //
  // if (!result.isValid()) {
  // return;
  // }
  //
  // this.getClientProtocol().addCoreNums(result.getRequest(),
  // groupcount.shortValue(), result.getReplicCount());
  //
  // this.addActionMessage(context, "??????" + groupcount + "??????????????????!!!!!");
  // }
  //
  // private boolean isRealTimeModel(Context context) throws Exception {
  // LocatedCores locateCore = this.getClientProtocol().getCoreLocations(
  // this.getAppDomain().getAppName());
  //
  // if (Corenodemanage.isRealTime(locateCore)) {
  // this.addErrorMessage(context, "???????????????????????????");
  // return true;
  // }
  //
  // return false;
  // }
  //
  // /**
  // * ?????????core????????????????????????
  // *
  // * @param context
  // * @throws Exception
  // */
  // @Func(PermissionConstant.APP_SERVER_SET)
  // public void doDeleteServerFromCore(Context context) throws Exception {
  //
  // Integer group = this.getInt("group");
  //
  // if (group == null || group < 0) {
  // this.addErrorMessage(context, "??????????????????Group");
  // return;
  // }
  //
  // final String ip = this.getString("ip");
  // if (StringUtils.isEmpty(ip)) {
  // this.addErrorMessage(context, "??????????????????????????????IP??????");
  // return;
  // }
  //
  // Matcher matcher = this.isValidIpPattern(ip);
  // if (!matcher.matches()) {
  // this.addErrorMessage(context, "IP:" + ip + "?????????????????????");
  // return;
  // }
  //
  // if (isDumpWorking(context)) {
  // return;
  // }
  //
  // // ??????????????????????????????
  // this.getClientProtocol().unprotectProcessExcessReplication(
  // this.getServiceName(), group, matcher.group(1));
  //
  // this.addActionMessage(context, "??????????????????server??????" + ip + " ??????" + group
  // + "???????????????????????????????????????????????????");
  // }
  //
  // public void doStopOne(Context context) throws Exception {
  // int groupNum = this.getInt("groupNum");
  // int replicaNum = this.getInt("replicaNum");
  // boolean success = false;
  // try {
  // success = this.getClientProtocol().stopOne(
  // this.getAppDomain().getAppName(), groupNum, replicaNum);
  // } catch (Exception e) {
  // }
  // if (success) {
  // this.addActionMessage(context, "???????????????????????? : "
  // + this.getAppDomain().getAppName() + "-" + groupNum + "-"
  // + replicaNum + ", ?????????????????? ,???????????????????????????????????????");
  // } else {
  // this.addErrorMessage(context, "??????????????????"
  // + this.getAppDomain().getAppName() + "-" + groupNum + "-"
  // + replicaNum + " ?????? , ?????????????????????");
  // }
  // }
  //
  // public void doStartOne(Context context) throws Exception {
  // int groupNum = this.getInt("groupNum");
  // int replicaNum = this.getInt("replicaNum");
  // boolean success = false;
  // try {
  // success = this.getClientProtocol().startOne(
  // this.getAppDomain().getAppName(), groupNum, replicaNum);
  // } catch (Exception e) {
  // }
  // if (success) {
  // this.addActionMessage(context, "???????????????????????? : "
  // + this.getAppDomain().getAppName() + "-" + groupNum + "-"
  // + replicaNum + ", ?????????????????? ,???????????????????????????????????????");
  // } else {
  // this.addErrorMessage(context, "??????????????????"
  // + this.getAppDomain().getAppName() + "-" + groupNum + "-"
  // + replicaNum + " ??????,?????????????????????");
  // }
  // }
  //
  // public void setCoreReplication(Context context) throws Exception {
  // Integer groupNum = this.getInt("groupNum");
  // Integer numReplica = this.getInt("numReplica");
  // CoreRequest request = createIps(context, this.getServiceName(), null);
  // boolean success = false;
  // try {
  // success = this.getClientProtocol().setCoreReplication(request,
  // groupNum.shortValue(), numReplica.shortValue());
  // } catch (Exception e) {
  // }
  // if (success) {
  // this.addActionMessage(context, "???????????? : "
  // + this.getAppDomain().getAppName() + "-" + groupNum
  // + "??????????????? : " + numReplica);
  // } else {
  // this.addErrorMessage(context, "??????"
  // + this.getAppDomain().getAppName() + "-" + groupNum
  // + "??????????????? : " + numReplica + " ?????? , ???????????????????????????????????????");
  // }
  // }
  //
  // public void doCheckingCoreCompletion(Context context) throws Exception {
  // CheckingResponse response = this.getClientProtocol()
  // .checkingCoreCompletion(this.getServiceName());
  // if (response.isFinished()) {
  // this.addActionMessage(context, "?????????????????? : "
  // + this.getAppDomain().getAppName() + ", ??????????????????????????????????????????");
  // } else if (response.isFailed()) {
  // this.addErrorMessage(context, "???????????? : "
  // + this.getAppDomain().getAppName() + " ??????,?????????????????????");
  // } else {
  // this.addErrorMessage(context, "processing");
  // }
  // }
}
