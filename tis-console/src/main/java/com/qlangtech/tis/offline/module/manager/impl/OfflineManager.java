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
package com.qlangtech.tis.offline.module.manager.impl;

import com.alibaba.citrus.turbine.Context;
import com.qlangtech.tis.TIS;
import com.qlangtech.tis.datax.impl.DataxReader;
import com.qlangtech.tis.db.parser.DBConfigSuit;
import com.qlangtech.tis.git.GitUtils;
import com.qlangtech.tis.git.GitUtils.GitBranchInfo;
import com.qlangtech.tis.git.GitUtils.GitUser;
import com.qlangtech.tis.git.GitUtils.JoinRule;
import com.qlangtech.tis.manage.biz.dal.pojo.Application;
import com.qlangtech.tis.manage.biz.dal.pojo.ApplicationCriteria;
import com.qlangtech.tis.manage.biz.dal.pojo.OperationLog;
import com.qlangtech.tis.manage.common.Option;
import com.qlangtech.tis.offline.DbScope;
import com.qlangtech.tis.offline.module.action.OfflineDatasourceAction;
import com.qlangtech.tis.offline.pojo.TISDb;
import com.qlangtech.tis.offline.pojo.WorkflowPojo;
import com.qlangtech.tis.plugin.IPluginStore;
import com.qlangtech.tis.plugin.KeyedPluginStore;
import com.qlangtech.tis.plugin.ds.*;
import com.qlangtech.tis.pubhook.common.RunEnvironment;
import com.qlangtech.tis.runtime.module.action.BasicModule;
import com.qlangtech.tis.sql.parser.SqlTaskNodeMeta;
import com.qlangtech.tis.util.IPluginContext;
import com.qlangtech.tis.workflow.dao.IWorkflowDAOFacade;
import com.qlangtech.tis.workflow.pojo.*;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.json.JSONObject;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ds???wf????????????db?????????
 *
 * @author ?????????baisui@qlangtech.com???
 * @date 2019???7???25???
 */
public class OfflineManager {

  private IWorkflowDAOFacade workflowDAOFacade;

  public static DataxReader getDBDataxReader(IPluginContext pluginContext, String dbName) {
    KeyedPluginStore<DataxReader> pluginStore = DataxReader.getPluginStore(pluginContext, true, dbName);
    return pluginStore.getPlugin();
  }

  public void setComDfireTisWorkflowDAOFacade(IWorkflowDAOFacade comDfireTisWorkflowDAOFacade) {
    this.workflowDAOFacade = comDfireTisWorkflowDAOFacade;
  }

  /**
   * ???????????????????????????
   *
   * @return ???????????????
   */
  public List<WorkFlow> getUsableWorkflow() {
    WorkFlowCriteria query = new WorkFlowCriteria();
    query.createCriteria();
    query.setOrderByClause("id desc");
    return this.workflowDAOFacade.getWorkFlowDAO().selectByExample(query, 1, 100);
  }

  /**
   * ??????DS???????????? ??????DataSource???DataXReader???DescriptorDisplayName
   *
   * @param dsName
   * @return
   */
  public DBDataXReaderDescName getDBDataXReaderDescName(String dsName) {
    IPluginStore<DataSourceFactory> dbPlugin = TIS.getDataBasePluginStore(new PostedDSProp(dsName, DbScope.DETAILED));
    DataSourceFactory.BaseDataSourceFactoryDescriptor descriptor
      = (DataSourceFactory.BaseDataSourceFactoryDescriptor) dbPlugin.getPlugin().getDescriptor();
    Optional<String> defaultDataXReaderDescName = descriptor.getDefaultDataXReaderDescName();
    return new DBDataXReaderDescName(defaultDataXReaderDescName, descriptor);
  }


  public interface IConnProcessor {

    public void vist(Connection conn) throws SQLException;
  }

  /**
   * @param table
   * @param action
   * @param context
   * @param updateMode
   * @param idempotent ???????????????table????????????
   * @return
   * @throws Exception
   */
  public ProcessedTable addDatasourceTable(TISTable table, BasicModule action, IPluginContext pluginContext, Context context, boolean updateMode, boolean idempotent) throws Exception {
    final String tableName = table.getTableName();
    // ??????db????????????
    final DatasourceDbCriteria dbCriteria = new DatasourceDbCriteria();
    dbCriteria.createCriteria().andIdEqualTo(table.getDbId());
    int sameDbCount = workflowDAOFacade.getDatasourceDbDAO().countByExample(dbCriteria);
    if (sameDbCount < 1) {
      action.addErrorMessage(context, "????????????????????????");
      return null;
    }
    final int dbId = table.getDbId();
    DatasourceTableCriteria tableCriteria = new DatasourceTableCriteria();
    int sameTableCount = 0;
    if (updateMode) {
      // ??????????????????????????????????????????????????????
      tableCriteria.createCriteria().andIdEqualTo(table.getTabId()).andNameEqualTo(table.getTableName());
      int findTable = workflowDAOFacade.getDatasourceTableDAO().countByExample(tableCriteria);
      if (findTable < 1) {
        throw new IllegalStateException("tabid:" + table.getTabId() + ",tabName:" + table.getTableName() + " is not exist in db");
      }
    } else {
      // ??????????????????????????????????????????
      tableCriteria.createCriteria().andDbIdEqualTo(dbId).andNameEqualTo(tableName);//.andTableLogicNameEqualTo();
      sameTableCount = workflowDAOFacade.getDatasourceTableDAO().countByExample(tableCriteria);
      if (!idempotent && sameTableCount > 0) {
        action.addErrorMessage(context, "????????????????????????????????????????????????:" + tableName);
        return null;
      }
    }
    // ??????DB?????????????????????
    DatasourceDb db = new DatasourceDb();
    // db.setId(dbId);
    db.setOpTime(new Date());
    int dbUpdateRows = workflowDAOFacade.getDatasourceDbDAO().updateByExampleSelective(db, dbCriteria);
    if (dbUpdateRows < 1) {
      throw new IllegalStateException("db update faild");
    }
    // ?????????db
    DatasourceTable dsTable = null;
    Integer tableId;
    if (updateMode) {
      if ((tableId = table.getTabId()) == null) {
        throw new IllegalStateException("update process tabId can not be null");
      }
      tableCriteria = new DatasourceTableCriteria();
      // .andNameEqualTo(table.getTableName());
      tableCriteria.createCriteria().andIdEqualTo(table.getTabId());
      dsTable = new DatasourceTable();
      dsTable.setOpTime(new Date());
      workflowDAOFacade.getDatasourceTableDAO().updateByExampleSelective(dsTable, tableCriteria);
      dsTable.setId(tableId);
    } else if (idempotent && sameTableCount > 0) {
      tableCriteria = new DatasourceTableCriteria();
      tableCriteria.createCriteria().andDbIdEqualTo(dbId).andNameEqualTo(tableName);
      for (DatasourceTable tab : workflowDAOFacade.getDatasourceTableDAO().selectByExample(tableCriteria)) {
        dsTable = tab;
      }
    } else {
      // sameTableCount < 0
      dsTable = new DatasourceTable();
      dsTable.setName(tableName);
//      dsTable.setTableLogicName(tableName);
      dsTable.setDbId(dbId);
      // dsTable.setGitTag(tableName);
      // ?????????????????????????????????
      // dsTable.setSyncOnline(new Byte("0"));
      dsTable.setCreateTime(new Date());
      dsTable.setOpTime(new Date());
      tableId = workflowDAOFacade.getDatasourceTableDAO().insertSelective(dsTable);
      dsTable.setId(tableId);
    }
    Objects.requireNonNull(dsTable, "dsTable can not be null");
    db = workflowDAOFacade.getDatasourceDbDAO().loadFromWriteDB(dbId);
    action.addActionMessage(context, "????????????'" + tableName + "'" + (updateMode ? "??????" : "??????") + "??????");
    table.setSelectSql(null);
    table.setReflectCols(null);
    table.setTabId(dsTable.getId());
    action.setBizResult(context, table);
    // return dsTable;
    DataSourceFactoryPluginStore dbPlugin = TIS.getDataBasePluginStore(new PostedDSProp(db.getName()));
    return new ProcessedTable(dbPlugin.saveTable(tableName), db, dsTable);
  }

  public static class ProcessedTable {
    private final TableReflect tabReflect;
    private final DatasourceTable tabMeta;
    private final DatasourceDb db;

    public ProcessedTable(TableReflect tabReflect, DatasourceDb db, DatasourceTable tabMeta) {
      if (tabReflect == null) {
        throw new IllegalStateException("tabReflect  can not be null");
      }
      if (tabMeta == null) {
        throw new IllegalStateException("tabMeta  can not be null");
      }
      if (db == null) {
        throw new IllegalArgumentException("param db can not be null");
      }
      this.tabReflect = tabReflect;
      this.tabMeta = tabMeta;
      this.db = db;
    }

    public String getDBName() {
      return this.db.getName();
    }

    public String getName() {
      return tabMeta.getName();
    }

    public Integer getDbId() {
      return this.tabMeta.getDbId();
    }

    public Integer getId() {
      return this.tabMeta.getId();
    }

    public String getExtraSql() {
      return SqlTaskNodeMeta.processBigContent(tabReflect.getSql());
    }
  }

  /**
   * ?????????????????????????????????????????????
   *
   * @param dbid
   * @param db
   * @param action
   * @param context
   */
  public void updateFacadeDBConfig(Integer dbid, TISDb db, BasicModule action, Context context) throws Exception {
    throw new UnsupportedOperationException();
//    if (dbid == null) {
//      throw new IllegalArgumentException("dbid can not be null");
//    }
//    DatasourceDb ds = workflowDAOFacade.getDatasourceDbDAO().loadFromWriteDB(dbid);
//    if (ds == null) {
//      throw new IllegalStateException("dbid:" + dbid + " relevant datasourceDB can not be null");
//    }
//    List<String> children = GitUtils.$().listDbConfigPath(ds.getName());
//    boolean isAdd = !children.contains(GitUtils.DB_CONFIG_META_NAME + DbScope.FACADE.getDBType());
//    if (StringUtils.isEmpty(db.getPassword())) {
//      if (isAdd) {
//        throw new IllegalStateException("db password can not be empty in add process");
//      } else {
//        // ????????????
//        DBConfig dbConfig = GitUtils.$().getDbLinkMetaData(ds.getName(), DbScope.FACADE);
//        db.setPassword(dbConfig.getPassword());
//      }
//    }
//    db.setFacade(true);
//    if (!this.testDbConnection(db, action, context).valid) {
//      return;
//    }
//    String path = GitUtils.$().getDBConfigPath(ds.getName(), DbScope.FACADE);
//    GitUtils.$().processDBConfig(db, path, "edit db" + db.getDbName(), isAdd, true);
  }

  /**
   * ????????????????????? date: 8:33 PM 6/15/2017
   */
  public void editDatasourceDb(TISDb db, BasicModule action, Context context) throws Exception {
    // ??????db????????????????????????
    String dbName = db.getDbName();
    if (StringUtils.isBlank(db.getDbId())) {
      throw new IllegalArgumentException("dbid can not be null");
    }
    DatasourceDb d = workflowDAOFacade.getDatasourceDbDAO().selectByPrimaryKey(// .minSelectByExample(criteria);
      Integer.parseInt(db.getDbId()));
    if (d == null) {
      action.addErrorMessage(context, "db???????????????????????????");
      return;
    }
    GitUtils.$().updateDatabase(db, "edit db " + db.getDbName());
    action.addActionMessage(context, "?????????????????????");
    action.setBizResult(context, db.getDbId());
  }

  public void editDatasourceTable(TISTable table, BasicModule action, IPluginContext pluginContext, Context context) throws Exception {
    String dbName = table.getDbName();
    String tableLogicName = table.getTableName();
    // ??????db????????????
    DatasourceDbCriteria dbCriteria = new DatasourceDbCriteria();
    dbCriteria.createCriteria().andNameEqualTo(dbName);
    List<DatasourceDb> dbList = workflowDAOFacade.getDatasourceDbDAO().minSelectByExample(dbCriteria);
    if (CollectionUtils.isEmpty(dbList)) {
      action.addErrorMessage(context, "????????????????????????");
      return;
    }
    int dbId = dbList.get(0).getId();
    // ?????????????????????
    DatasourceTableCriteria tableCriteria = new DatasourceTableCriteria();
    tableCriteria.createCriteria().andDbIdEqualTo(dbId).andNameEqualTo(tableLogicName);
    List<DatasourceTable> tableList = workflowDAOFacade.getDatasourceTableDAO().minSelectByExample(tableCriteria);
    if (CollectionUtils.isEmpty(tableList)) {
      action.addErrorMessage(context, "????????????????????????" + tableLogicName);
      return;
    }
    int tableId = tableList.get(0).getId();
    DataSourceFactoryPluginStore dbPlugin = TIS.getDataBasePluginStore(new PostedDSProp(dbName));
    dbPlugin.saveTable(tableLogicName);
    // update git
    // String path = dbName + "/" + tableLogicName;
    // GitUtils.$().createTableDaily(table, "edit table " + table.getTableLogicName());
    OperationLog operationLog = new OperationLog();
    operationLog.setUsrName(action.getLoginUserName());
    operationLog.setUsrId(action.getUserId());
    operationLog.setOpType("editDatasourceTable");
    action.addActionMessage(context, "????????????????????????");
    action.setBizResult(context, tableId);
  }

  /**
   * description: ????????????????????????????????? date: 2:30 PM 4/28/2017
   */
  public List<Option> getUsableDbNames() {
    DatasourceDbCriteria criteria = new DatasourceDbCriteria();
    criteria.createCriteria();
    List<DatasourceDb> dbList = workflowDAOFacade.getDatasourceDbDAO().selectByExample(criteria);
    List<Option> dbNameList = new LinkedList<>();
    for (DatasourceDb datasourceDb : dbList) {
      dbNameList.add(new Option(datasourceDb.getName(), String.valueOf(datasourceDb.getId())));
    }
    return dbNameList;
  }

  /**
   * description: ?????????????????? date: 6:21 PM 5/18/2017
   */
  public List<DatasourceTable> getDatasourceTables() {
    DatasourceTableCriteria criteria = new DatasourceTableCriteria();
    criteria.createCriteria();
    List<DatasourceTable> tables = workflowDAOFacade.getDatasourceTableDAO().selectByExample(criteria);
    DatasourceDbCriteria dbCriteria = new DatasourceDbCriteria();
    dbCriteria.createCriteria();
    List<DatasourceDb> dbs = workflowDAOFacade.getDatasourceDbDAO().selectByExample(dbCriteria);
    Map<Integer, DatasourceDb> dbMap = new HashMap<>();
    for (DatasourceDb datasourceDb : dbs) {
      dbMap.put(datasourceDb.getId(), datasourceDb);
    }
    // ???????????????????????????
    Map<String, Integer> tableNameCntMap = new HashMap<>();
    for (DatasourceTable datasourceTable : tables) {
      String name = datasourceTable.getName();
      if (tableNameCntMap.containsKey(name)) {
        tableNameCntMap.put(name, tableNameCntMap.get(name) + 1);
      } else {
        tableNameCntMap.put(name, 1);
      }
    }
    for (DatasourceTable datasourceTable : tables) {
      String name = datasourceTable.getName();
      if (tableNameCntMap.get(name) > 1) {
        datasourceTable.setName(dbMap.get(datasourceTable.getDbId()).getName() + "/" + datasourceTable.getName());
      }
    }
    Collections.sort(tables);
    return tables;
  }

  public void editWorkflow(WorkflowPojo pojo, BasicModule action, Context context) throws Exception {
    String name = pojo.getName();
    WorkFlowCriteria criteria = new WorkFlowCriteria();
    criteria.createCriteria().andNameEqualTo(name);
    List<WorkFlow> workflowList = workflowDAOFacade.getWorkFlowDAO().selectByExample(criteria);
    // 1?????????????????????
    if (CollectionUtils.isEmpty(workflowList)) {
      action.addErrorMessage(context, "???????????????" + name + "????????????");
      return;
    }
    WorkFlow workFlow = workflowList.get(0);
    if (workFlow.getInChange().intValue() == 0) {
      action.addErrorMessage(context, "????????????????????????");
      return;
    }
    // 2?????????xml????????????
    JoinRule task = pojo.getTask();
    if (task == null || StringUtils.isBlank(task.getContent())) {
      action.addErrorMessage(context, "????????????????????????");
      return;
    }
    if (!isXmlValid(task)) {
      action.addErrorMessage(context, "XML????????????????????????XML??????");
      return;
    }
    JSONObject jsonObject = new JSONObject();
    jsonObject.put("name", name);
    // jsonObject.put("tables", StringUtils.join(pojo.getDependTableIds(), ","));
    jsonObject.put("task", pojo.getTask());
    try {
      GitUtils.$().updateWorkflowFile(name, name, jsonObject.toString(1), "update workflow " + name);
    } catch (Exception e) {
      action.addErrorMessage(context, "git????????????????????????");
      action.addErrorMessage(context, e.getMessage());
    }
  }

  /**
   * description: ???????????????????????? date: 7:43 PM 5/19/2017
   */
  public Collection<OfflineDatasourceAction.DatasourceDb> getDatasourceInfo() throws Exception {
    DatasourceDbCriteria criteria = new DatasourceDbCriteria();
    criteria.createCriteria();
    List<DatasourceDb> dbList = workflowDAOFacade.getDatasourceDbDAO().selectByExample(criteria);
    DatasourceTableCriteria tableCriteria = new DatasourceTableCriteria();
    tableCriteria.createCriteria();
    // List<DatasourceTable> tableList = workflowDAOFacade.getDatasourceTableDAO().selectByExample(tableCriteria);
    Map<Integer, OfflineDatasourceAction.DatasourceDb> dbsMap = new HashMap<>();
    for (DatasourceDb db : dbList) {
      OfflineDatasourceAction.DatasourceDb datasourceDb1 = new OfflineDatasourceAction.DatasourceDb();
      datasourceDb1.setId(db.getId());
      datasourceDb1.setName(db.getName());
      dbsMap.put(db.getId(), datasourceDb1);
    }
//    for (DatasourceTable table : tableList) {
//      int dbId = table.getDbId();
//      if (dbsMap.containsKey(dbId)) {
//        OfflineDatasourceAction.DatasourceDb datasourceDb = dbsMap.get(dbId);
//        datasourceDb.addTable(table);
//      }
//    }
    return dbsMap.values();
  }

  public DBConfigSuit getDbConfig(IPluginContext pluginContext, DatasourceDb db) {
    Objects.requireNonNull(db, "instance of DatasourceDb can not be null");

    DBDataXReaderDescName dbDataXReaderDesc = this.getDBDataXReaderDescName(db.getName());
    DataxReader dbDataxReader = null;
    if (dbDataXReaderDesc.isSupportDataXReader()) {
      dbDataxReader = getDBDataxReader(pluginContext, db.getName());
    }
    DBConfigSuit dbSuit = new DBConfigSuit(db, dbDataXReaderDesc.isSupportDataXReader(), dbDataxReader != null);

    if (dbDataxReader != null) {
      List<ISelectedTab> selectedTabs = dbDataxReader.getSelectedTabs();
      dbSuit.addTabs(selectedTabs.stream()
        .map((t) -> t.getName()).collect(Collectors.toList()));
    }

    PostedDSProp dbProp = new PostedDSProp(db.getName(), DbScope.DETAILED);

    IPluginStore<DataSourceFactory> dbStore = TIS.getDataBasePluginStore(dbProp);

    DataSourceFactory dsPlugin = dbStore.getPlugin();
    dbSuit.setDetailed(dsPlugin);
    DataSourceFactoryPluginStore facadeStore
      = TIS.getDataBasePluginStore(new PostedDSProp(db.getName(), DbScope.FACADE));

    if (facadeStore.getPlugin() != null) {
      dbSuit.setFacade(facadeStore.getPlugin());
    }
    return dbSuit;
  }

  public DBConfigSuit getDbConfig(IPluginContext pluginContext, Integer dbId) {
    return this.getDbConfig(pluginContext, getDB(dbId));
  }

  public DatasourceDb getDB(Integer dbId) {
    DatasourceDb db = workflowDAOFacade.getDatasourceDbDAO().selectByPrimaryKey(dbId);
    if (db == null) {
      throw new IllegalStateException("dbid:" + dbId + " can not find relevant db object in DB");
    }
    return db;
  }

//  public TISTable getTableConfig(IPluginContext pluginContext, Integer tableId) {
//    DatasourceTable tab = this.workflowDAOFacade.getDatasourceTableDAO().selectByPrimaryKey(tableId);
//    DatasourceDb db = this.workflowDAOFacade.getDatasourceDbDAO().selectByPrimaryKey(tab.getDbId());
//    DataSourceFactoryPluginStore dbPlugin = TIS.getDataBasePluginStore(new PostedDSProp(db.getName()));
//    TISTable t = dbPlugin.loadTableMeta(tab.getName());
//    t.setDbName(db.getName());
//    t.setTableName(tab.getName());
//    t.setTabId(tableId);
//    t.setDbId(db.getId());
//    return t;
//  }

  public WorkflowPojo getWorkflowConfig(Integer workflowId, boolean isMaster) {
    WorkFlow workFlow = this.workflowDAOFacade.getWorkFlowDAO().selectByPrimaryKey(workflowId);
    if (workFlow == null) {
      throw new IllegalStateException("workflow obj is null");
    }
    return GitUtils.$().getWorkflow(workFlow.getName(), GitBranchInfo.$("xxxxxxxxxxxxxx"));
  }

  // public WorkflowPojo getWorkflowConfig(String name, boolean isMaster) {
  // return
  // }
  // public WorkflowPojo getWorkflowConfig(String name, String sha) {
  // return GitUtils.$().getWorkflowSha(GitUtils.WORKFLOW_GIT_PROJECT_ID, sha,
  // name);
  // }
  public void deleteWorkflow(int id, BasicModule action, Context context) {
    WorkFlow workFlow = workflowDAOFacade.getWorkFlowDAO().selectByPrimaryKey(id);
    if (workFlow == null) {
      action.addErrorMessage(context, "???????????????????????????");
      return;
    }
    if (workFlow.getInChange().intValue() != 0) {
      action.addErrorMessage(context, "???????????????????????????????????????");
      return;
    }
    ApplicationCriteria applicationCriteria = new ApplicationCriteria();
    applicationCriteria.createCriteria().andWorkFlowIdEqualTo(id);
    List<Application> applications = action.getApplicationDAO().selectByExample(applicationCriteria);
    if (!CollectionUtils.isEmpty(applications)) {
      StringBuilder stringBuilder = new StringBuilder();
      for (Application application : applications) {
        stringBuilder.append(application.getProjectName()).append(", ");
      }
      action.addErrorMessage(context, "????????????????????????????????????????????????????????????" + stringBuilder.toString());
      return;
    }
    try {
      GitUser user = GitUser.dft();
      // delete git
      GitUtils.$().deleteWorkflow(workFlow.getName(), user);
      // delete db
      workflowDAOFacade.getWorkFlowDAO().deleteByPrimaryKey(id);
      // TODO ????????????db????????????????????????http??????
      action.addActionMessage(context, "?????????????????????");
    } catch (Exception e) {
      action.addErrorMessage(context, "?????????????????????");
      action.addErrorMessage(context, e.getMessage());
    }
  }

  /**
   * ??????????????????????????????
   *
   * @param tableLogicName
   * @return
   */
  public boolean checkTableLogicNameRepeat(String tableLogicName, DatasourceDb db) {
    if (db == null) {
      throw new IllegalStateException(" database can not be null");
    }
    DatasourceTableCriteria criteria = new DatasourceTableCriteria();
    criteria.createCriteria().andNameEqualTo(tableLogicName).andDbIdEqualTo(db.getId());
    int tableCount = workflowDAOFacade.getDatasourceTableDAO().countByExample(criteria);
    return tableCount > 0;
  }

  //
  // public void syncDb(int id, String dbName, BasicModule action, Context
  // context) {
  // DatasourceDbCriteria criteria = new DatasourceDbCriteria();
  // criteria.createCriteria().andIdEqualTo(id).andNameEqualTo(dbName);
  // List<DatasourceDb> dbList =
  // this.workflowDAOFacade.getDatasourceDbDAO().selectByExample(criteria);
  //
  // // 1. ??????????????????????????????
  // if (CollectionUtils.isEmpty(dbList)) {
  // action.addErrorMessage(context, "????????????????????????id???" + id + "??????????????????" + dbName);
  // return;
  // }
  //
  // // 2. ????????????????????????
  // DatasourceDb datasourceDb = dbList.get(0);
  // if (datasourceDb.getSyncOnline().intValue() == 1) {
  // action.addErrorMessage(context, "???????????????????????????");
  // return;
  // }
  //
  // // 3. ??????git???????????????????????????
  // String gitPah = dbName + "/db_config";
  // boolean onlineGitFileExisted =
  // GitUtils.$().isFileExisted(GitUtils.DATASOURCE_PROJECT_ID_ONLINE,
  // gitPah);
  // if (onlineGitFileExisted) {
  // action.addErrorMessage(context, "??????git???????????????????????????????????????");
  // return;
  // }
  //
  // // 4. git???????????????????????????
  //
  // try {
  // String file = GitUtils.$().getFileContent(GitUtils.DATASOURCE_PROJECT_ID,
  // gitPah);
  // GitUtils.$().createDatasourceFileOnline(gitPah, file, "add db " +
  // dbName);
  // } catch (Exception e) {
  // action.addErrorMessage(context, "git?????????????????????????????????");
  // return;
  // }
  //
  // // 5. db?????????????????????
  // // TODO ????????????????????????
  // List<HttpUtils.PostParam> params = new LinkedList<>();
  // params.add(new HttpUtils.PostParam("id",
  // datasourceDb.getId().toString()));
  // params.add(new HttpUtils.PostParam("name", datasourceDb.getName()));
  // String url = URL_ONLINE +
  // "&&event_submit_do_sync_db_record=true&resulthandler=advance_query_result";
  // try {
  // HttpUtils.post(new URL(url), params, new PostFormStreamProcess<Boolean>()
  // {
  //
  // public Boolean p(int status, InputStream stream, String md5) {
  // try {
  // return Boolean.parseBoolean(IOUtils.toString(stream, "utf8"));
  // } catch (IOException e) {
  // throw new RuntimeException(e);
  // }
  // }
  // });
  // } catch (MalformedURLException e) {
  // e.printStackTrace();
  // }
  //
  // // 6. ??????db????????????
  // datasourceDb.setSyncOnline(new Byte("1"));
  // this.workflowDAOFacade.getDatasourceDbDAO().updateByExample(datasourceDb,
  // criteria);
  // }
  //
  // public void syncTable(int id, String tableLogicName, BasicModule action,
  // Context context) {
  // DatasourceTableCriteria criteria = new DatasourceTableCriteria();
  // criteria.createCriteria().andIdEqualTo(id).andTableLogicNameEqualTo(tableLogicName);
  // List<DatasourceTable> tableList =
  // this.workflowDAOFacade.getDatasourceTableDAO().selectByExample(criteria);
  //
  // // 1. ??????????????????????????????
  // if (CollectionUtils.isEmpty(tableList)) {
  // action.addErrorMessage(context, "???????????????????????????id???" + id + "????????????????????????" +
  // tableLogicName);
  // return;
  // }
  //
  // // 2. ????????????????????????
  // DatasourceTable datasourceTable = tableList.get(0);
  // if (datasourceTable.getSyncOnline().intValue() == 1) {
  // action.addErrorMessage(context, "??????????????????????????????");
  // return;
  // }
  // int dbId = datasourceTable.getDbId();
  // DatasourceDb datasourceDb =
  // this.workflowDAOFacade.getDatasourceDbDAO().selectByPrimaryKey(dbId);
  //
  // // 3. ??????git???????????????????????????
  // String gitPah = datasourceDb.getName() + "/" + tableLogicName;
  // boolean onlineGitFileExisted =
  // GitUtils.$().isFileExisted(GitUtils.DATASOURCE_PROJECT_ID_ONLINE,
  // gitPah);
  // if (onlineGitFileExisted) {
  // action.addErrorMessage(context, "??????git??????????????????????????????????????????");
  // return;
  // }
  //
  // // 4. git???????????????????????????
  // try {
  // String file = GitUtils.$().getFileContent(GitUtils.DATASOURCE_PROJECT_ID,
  // gitPah);
  // GitUtils.$().createDatasourceFileOnline(gitPah, file, "add table " +
  // gitPah);
  // } catch (Exception e) {
  // action.addErrorMessage(context, "git?????????????????????????????????");
  // return;
  // }
  //
  // // 5. db?????????????????????
  // // TODO ????????????????????????
  // List<HttpUtils.PostParam> params = new LinkedList<>();
  // params.add(new HttpUtils.PostParam("id",
  // datasourceTable.getId().toString()));
  // params.add(new HttpUtils.PostParam("name", datasourceTable.getName()));
  // params.add(new HttpUtils.PostParam("table_logic_name",
  // datasourceTable.getTableLogicName()));
  // params.add(new HttpUtils.PostParam("db_id",
  // datasourceTable.getDbId().toString()));
  // params.add(new HttpUtils.PostParam("git_tag",
  // datasourceTable.getGitTag()));
  // String url = URL_ONLINE +
  // "&&event_submit_do_sync_table_record=true&resulthandler=advance_query_result";
  // try {
  // HttpUtils.post(new URL(url), params, new PostFormStreamProcess<Boolean>()
  // {
  //
  // public Boolean p(int status, InputStream stream, String md5) {
  // try {
  // return Boolean.parseBoolean(IOUtils.toString(stream, "utf8"));
  // } catch (IOException e) {
  // throw new RuntimeException(e);
  // }
  // }
  // });
  // } catch (MalformedURLException e) {
  // e.printStackTrace();
  // }
  //
  // // 6. ??????db????????????
  // datasourceTable.setSyncOnline(new Byte("1"));
  // this.workflowDAOFacade.getDatasourceTableDAO().updateByExample(datasourceTable,
  // criteria);
  // }
  public void syncDbRecord(DatasourceDb datasourceDb, BasicModule action, Context context) {
    try {
      this.workflowDAOFacade.getDatasourceDbDAO().insertSelective(datasourceDb);
      action.setBizResult(context, true);
    } catch (Exception e) {
      action.addErrorMessage(context, e.getMessage());
      action.setBizResult(context, false);
    }
  }

  public void syncTableRecord(DatasourceTable datasourceTable, BasicModule action, Context context) {
    try {
      this.workflowDAOFacade.getDatasourceTableDAO().insertSelective(datasourceTable);
      action.setBizResult(context, true);
    } catch (Exception e) {
      action.addErrorMessage(context, e.getMessage());
      action.setBizResult(context, false);
    }
  }

  private static boolean isXmlValid(JoinRule xmlStr) {
    boolean result = true;
    try {
      StringReader sr = new StringReader(xmlStr.getContent());
      InputSource is = new InputSource(sr);
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      DocumentBuilder builder = factory.newDocumentBuilder();
      builder.parse(is);
    } catch (Exception e) {
      result = false;
    }
    return result;
  }

  // /**
  // * description: ?????????table???dump?????? date: 11:20 AM 6/17/2017
  // */
  // public void disableTableDump(int tableId, BasicModule action, Context context) {
  // TableDumpCriteria criteria = new TableDumpCriteria();
  // criteria.createCriteria().andDatasourceTableIdEqualTo(tableId).andStateEqualTo(new Byte("1")).andIsValidEqualTo(new Byte("1"));
  // criteria.setOrderByClause("op_time desc");
  // // ??????????????????????????????dump
  // List<TableDump> tableDumps = this.workflowDAOFacade.getTableDumpDAO().selectByExampleWithoutBLOBs(criteria, 1, 1);
  // if (CollectionUtils.isEmpty(tableDumps)) {
  // return;
  // }
  // TableDump tableDump = tableDumps.get(0);
  // tableDump.setIsValid(new Byte("0"));
  // criteria = new TableDumpCriteria();
  // criteria.createCriteria().andIdEqualTo(tableDump.getId());
  // // ?????????
  // this.workflowDAOFacade.getTableDumpDAO().updateByExampleSelective(tableDump, criteria);
  // }
  // /**
  // * description: ?????????db?????????table???dump?????? date: 11:20 AM 6/17/2017
  // */
  // public void disableDbDump(int dbId, BasicModule action, Context context) {
  // DatasourceTableCriteria tableCriteria = new DatasourceTableCriteria();
  // tableCriteria.createCriteria().andDbIdEqualTo(dbId);
  // List<DatasourceTable> datasourceTables = this.workflowDAOFacade.getDatasourceTableDAO().selectByExample(tableCriteria);
  // if (CollectionUtils.isEmpty(datasourceTables)) {
  // return;
  // }
  // // ????????????table??????
  // for (DatasourceTable datasourceTable : datasourceTables) {
  // this.disableTableDump(datasourceTable.getId(), action, context);
  // }
  // }
  public void deleteDbById(int dbId, DbScope dbModel, BasicModule action, Context context) throws Exception {
    // 1 ?????????db????????????
    DatasourceDb db = this.workflowDAOFacade.getDatasourceDbDAO().selectByPrimaryKey(dbId);
    if (db == null) {
      action.addErrorMessage(context, "????????????db???db id = " + dbId);
      return;
    }

    // 2 ??????????????????
    DatasourceTableCriteria tableCriteria = new DatasourceTableCriteria();
    tableCriteria.createCriteria().andDbIdEqualTo(dbId);
    List<DatasourceTable> datasourceTables = this.workflowDAOFacade.getDatasourceTableDAO().minSelectByExample(tableCriteria);
    if (dbModel == DbScope.DETAILED && !CollectionUtils.isEmpty(datasourceTables)) {
      action.addErrorMessage(context, "????????????????????????????????????????????????????????????");
      return;
    }
//    DataSourceFactoryPluginStore dsPluginStore = TIS.getDataBasePluginStore(new PostedDSProp(db.getName()));
//    dsPluginStore.deleteDB();
    TIS.deleteDB(db.getName(), dbModel);
    // GitUser user = GitUser.dft();
    // 3 ??????git
    //GitUtils.$().deleteDb(db.getName(), user);
    // 4 ??????db
    if (dbModel == DbScope.DETAILED) {
      this.workflowDAOFacade.getDatasourceDbDAO().deleteByPrimaryKey(dbId);
    }
    action.addActionMessage(context, "????????????'" + db.getName() + "'");
  }

  public void deleteDatasourceTableById(int tableId, BasicModule action, Context context) {
    // 1 ?????????????????????
    DatasourceTable datasourceTable = this.workflowDAOFacade.getDatasourceTableDAO().selectByPrimaryKey(tableId);
    if (datasourceTable == null) {
      action.addErrorMessage(context, "??????????????????table id = " + tableId);
      return;
    }
    // 2 ???????????????db????????????
    Integer dbId = datasourceTable.getDbId();
    DatasourceDb datasourceDb = this.workflowDAOFacade.getDatasourceDbDAO().selectByPrimaryKey(dbId);
    if (datasourceDb == null) {
      action.addErrorMessage(context, "????????????????????????????????????db id = " + dbId);
      return;
    }
    // 3 ????????????????????????????????????table
    WorkFlowCriteria workFlowCriteria = new WorkFlowCriteria();
    workFlowCriteria.createCriteria();
    List<WorkFlow> workFlows = this.workflowDAOFacade.getWorkFlowDAO().selectByExample(workFlowCriteria);
    for (WorkFlow workFlow : workFlows) {
      // WorkflowPojo workflowPojo = getWorkflowConfig(workFlow.getName(), true);
      // if (!CollectionUtils.isEmpty(workflowPojo.getDependTableIds())
      // && workflowPojo.getDependTableIds().contains(datasourceTable.getId())) {
      // action.addErrorMessage(context,
      // "?????????????????????" + datasourceTable.getTableLogicName() + "??????????????????" +
      // workflowPojo.getName() + "??????");
      // return;
      // }
    }
    // 4 ??????git
    try {
      GitUser user = GitUser.dft();
      RunEnvironment runEnvironment = action.getAppDomain().getRunEnvironment();
      if (RunEnvironment.DAILY.equals(runEnvironment)) {
        GitUtils.$().deleteTableDaily(datasourceDb.getName(), datasourceTable.getName(), user);
      } else if (RunEnvironment.ONLINE.equals(runEnvironment)) {
        GitUtils.$().deleteTableOnline(datasourceDb.getName(), datasourceTable.getName(), user);
      } else {
        action.addErrorMessage(context, "??????????????????" + runEnvironment + "?????????daily?????????online");
        return;
      }
    } catch (Exception e) {
      action.addErrorMessage(context, "?????????????????????");
      action.addErrorMessage(context, e.getMessage());
      return;
    }
    // 5 ??????db
    this.workflowDAOFacade.getDatasourceTableDAO().deleteByPrimaryKey(tableId);
    action.addActionMessage(context, "????????????table");
  }

  public void deleteWorkflowChange(int workflowId, BasicModule action, Context context) {
    WorkFlow workFlow = this.workflowDAOFacade.getWorkFlowDAO().selectByPrimaryKey(workflowId);
    if (workFlow == null) {
      action.addErrorMessage(context, "?????????id???" + workflowId + "????????????");
      return;
    }
    if (workFlow.getInChange().intValue() == 0) {
      action.addErrorMessage(context, "id???" + workflowId + "???????????????????????????");
      return;
    }
    try {
      GitUtils.$().deleteWorkflowBranch(workFlow.getName());
    } catch (Exception e) {
      e.printStackTrace();
      action.addErrorMessage(context, "????????????" + workFlow.getName() + "??????");
      action.addErrorMessage(context, e.getMessage());
      return;
    }
    int inChange = workFlow.getInChange().intValue();
    if (inChange == 1) {
      // ???????????????????????? ????????????db
      this.workflowDAOFacade.getWorkFlowDAO().deleteByPrimaryKey(workflowId);
    } else if (inChange == 2) {
      // ?????????????????????????????????
      workFlow.setInChange(new Byte("0"));
      WorkFlowCriteria criteria = new WorkFlowCriteria();
      criteria.createCriteria().andIdEqualTo(workflowId);
      this.workflowDAOFacade.getWorkFlowDAO().updateByExample(workFlow, criteria);
    }
    // ??????????????????
//    WorkFlowPublishHistoryCriteria criteria1 = new WorkFlowPublishHistoryCriteria();
//    criteria1.createCriteria().andWorkflowIdEqualTo(workflowId).andPublishStateEqualTo(new Byte("3"));
//    List<WorkFlowPublishHistory> workFlowPublishHistories = this.workflowDAOFacade.getWorkFlowPublishHistoryDAO().selectByExampleWithoutBLOBs(criteria1);
//    if (CollectionUtils.isEmpty(workFlowPublishHistories)) {
//      action.addErrorMessage(context, "???????????????????????????");
//      return;
//    } else {
//      WorkFlowPublishHistory workFlowPublishHistory = workFlowPublishHistories.get(0);
//      workFlowPublishHistory.setPublishState(new Byte("2"));
//      this.workflowDAOFacade.getWorkFlowPublishHistoryDAO().updateByExampleWithoutBLOBs(workFlowPublishHistory, criteria1);
//    }
    action.addActionMessage(context, "??????????????????");
  }

  public void confirmWorkflowChange(int workflowId, BasicModule action, Context context) {
    WorkFlow workFlow = this.workflowDAOFacade.getWorkFlowDAO().selectByPrimaryKey(workflowId);
    if (workFlow == null) {
      action.addErrorMessage(context, "?????????id???" + workflowId + "????????????");
      return;
    }
    int inChange = workFlow.getInChange().intValue();
    if (inChange == 0) {
      action.addErrorMessage(context, "id???" + workflowId + "???????????????????????????");
      return;
    }
    // git ??????
    try {
      GitUtils.$().mergeWorkflowChange(workFlow.getName());
    } catch (Exception e) {
      action.addErrorMessage(context, "git??????????????????");
      action.addErrorMessage(context, "??????????????????????????????????????????????????????");
      action.addErrorMessage(context, e.getMessage());
      return;
    }
    // db ??????
    if (inChange == 1) {
      // ???????????????????????? ???????????????
      // TODO ??????????????????????????????console ??????????????????db??????
    }
    // ?????????????????????????????????
    workFlow.setInChange(new Byte("0"));
    WorkFlowCriteria criteria = new WorkFlowCriteria();
    criteria.createCriteria().andIdEqualTo(workflowId);
    this.workflowDAOFacade.getWorkFlowDAO().updateByExample(workFlow, criteria);
    // ????????????
//    WorkFlowPublishHistoryCriteria criteria1 = new WorkFlowPublishHistoryCriteria();
//    criteria1.createCriteria().andWorkflowIdEqualTo(workflowId).andPublishStateEqualTo(new Byte("3"));
//    List<WorkFlowPublishHistory> workFlowPublishHistories = this.workflowDAOFacade.getWorkFlowPublishHistoryDAO().selectByExampleWithoutBLOBs(criteria1);
//    if (CollectionUtils.isEmpty(workFlowPublishHistories)) {
//      action.addErrorMessage(context, "???????????????????????????");
//      return;
//    } else {
//      // ???????????????????????????????????????
//      WorkFlowPublishHistoryCriteria inUseCriteria = new WorkFlowPublishHistoryCriteria();
//      inUseCriteria.createCriteria().andWorkflowIdEqualTo(workflowId).andInUseEqualTo(true);
//      WorkFlowPublishHistory notInUse = new WorkFlowPublishHistory();
//      notInUse.setInUse(false);
//      this.workflowDAOFacade.getWorkFlowPublishHistoryDAO().updateByExampleSelective(notInUse, inUseCriteria);
//      // ?????????????????????????????????
//      WorkFlowPublishHistory workFlowPublishHistory = workFlowPublishHistories.get(0);
//      workFlowPublishHistory.setPublishState(new Byte("1"));
//      workFlowPublishHistory.setGitSha1(GitUtils.$().getLatestSha(GitUtils.WORKFLOW_GIT_PROJECT_ID));
//      workFlowPublishHistory.setInUse(true);
//      this.workflowDAOFacade.getWorkFlowPublishHistoryDAO().updateByExampleSelective(workFlowPublishHistory, criteria1);
//    }
    action.addActionMessage(context, "??????????????????");
  }

  public static class DBDataXReaderDescName {
    public final Optional<String> readerDescName;
    public final DataSourceFactory.BaseDataSourceFactoryDescriptor dsDescriptor;

    public DBDataXReaderDescName(Optional<String> readerDescName, DataSourceFactory.BaseDataSourceFactoryDescriptor dsDescriptor) {
      this.readerDescName = readerDescName;
      this.dsDescriptor = dsDescriptor;
    }

    public boolean isSupportDataXReader() {
      return readerDescName.isPresent();
    }

    public String getReaderDescName() {
      return this.readerDescName.get();
    }
  }
}
