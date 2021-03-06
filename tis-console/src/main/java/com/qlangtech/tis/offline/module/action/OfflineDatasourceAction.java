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
package com.qlangtech.tis.offline.module.action;

import com.alibaba.citrus.turbine.Context;
import com.alibaba.fastjson.JSON;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.koubei.web.tag.pager.Pager;
import com.qlangtech.tis.TIS;
import com.qlangtech.tis.assemble.FullbuildPhase;
import com.qlangtech.tis.common.utils.Assert;
import com.qlangtech.tis.coredefine.module.action.CoreAction;
import com.qlangtech.tis.coredefine.module.action.DataxAction;
import com.qlangtech.tis.coredefine.module.action.PluginDescMeta;
import com.qlangtech.tis.datax.impl.DataxReader;
import com.qlangtech.tis.db.parser.DBConfigSuit;
import com.qlangtech.tis.extension.Descriptor;
import com.qlangtech.tis.extension.DescriptorExtensionList;
import com.qlangtech.tis.extension.PluginFormProperties;
import com.qlangtech.tis.extension.impl.PropertyType;
import com.qlangtech.tis.extension.impl.SuFormProperties;
import com.qlangtech.tis.fullbuild.IFullBuildContext;
import com.qlangtech.tis.git.GitUtils;
import com.qlangtech.tis.git.GitUtils.JoinRule;
import com.qlangtech.tis.manage.PermissionConstant;
import com.qlangtech.tis.manage.common.AppDomainInfo;
import com.qlangtech.tis.manage.common.HttpUtils.PostParam;
import com.qlangtech.tis.manage.common.IUser;
import com.qlangtech.tis.manage.common.Option;
import com.qlangtech.tis.manage.spring.aop.Func;
import com.qlangtech.tis.offline.DbScope;
import com.qlangtech.tis.offline.module.manager.impl.OfflineManager;
import com.qlangtech.tis.offline.pojo.GitRepositoryCommitPojo;
import com.qlangtech.tis.offline.pojo.TISDb;
import com.qlangtech.tis.offline.pojo.WorkflowPojo;
import com.qlangtech.tis.plugin.IPluginStore;
import com.qlangtech.tis.plugin.annotation.FormFieldType;
import com.qlangtech.tis.plugin.annotation.Validator;
import com.qlangtech.tis.plugin.ds.*;
import com.qlangtech.tis.runtime.module.action.BasicModule;
import com.qlangtech.tis.runtime.module.misc.IControlMsgHandler;
import com.qlangtech.tis.runtime.module.misc.IFieldErrorHandler;
import com.qlangtech.tis.runtime.module.misc.impl.DelegateControl4JsonPostMsgHandler;
import com.qlangtech.tis.sql.parser.SqlTaskNode;
import com.qlangtech.tis.sql.parser.SqlTaskNodeMeta;
import com.qlangtech.tis.sql.parser.SqlTaskNodeMeta.SqlDataFlowTopology;
import com.qlangtech.tis.sql.parser.er.ERRules;
import com.qlangtech.tis.sql.parser.er.PrimaryTableMeta;
import com.qlangtech.tis.sql.parser.er.TabCardinality;
import com.qlangtech.tis.sql.parser.er.TableRelation;
import com.qlangtech.tis.sql.parser.exception.TisSqlFormatException;
import com.qlangtech.tis.sql.parser.meta.*;
import com.qlangtech.tis.util.DescriptorsJSON;
import com.qlangtech.tis.util.HeteroList;
import com.qlangtech.tis.util.UploadPluginMeta;
import com.qlangtech.tis.workflow.dao.IWorkFlowDAO;
import com.qlangtech.tis.workflow.dao.IWorkflowDAOFacade;
import com.qlangtech.tis.workflow.pojo.DatasourceTable;
import com.qlangtech.tis.workflow.pojo.WorkFlow;
import com.qlangtech.tis.workflow.pojo.WorkFlowCriteria;
import name.fraser.neil.plaintext.diff_match_patch;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.qlangtech.tis.sql.parser.er.ERRules.$;
import static java.sql.Types.*;

/**
 * ????????????????????????
 * <ul>
 * <li>datasource</li>
 * <li>table</li>
 * </ul>
 *
 * @author ?????????baisui@qlangtech.com???
 * @date 2019???7???25???
 */
public class OfflineDatasourceAction extends BasicModule {

  private static final Pattern pattern_table_name = Pattern.compile("[a-z]{1}[\\da-z_]+");

  private static final long serialVersionUID = 1L;

  private OfflineManager offlineManager;

  private static final int COMMITS_LIMIT = 20;

  private static final Pattern DB_ENUM_PATTERN = Pattern.compile("(\\w+?)(\\d+)");

  private static final Pattern WORD_CHARACTER_PATTERN = Pattern.compile("\\w*");

  private static final diff_match_patch DIFF_MATCH_PATCH = new diff_match_patch();

  private IWorkflowDAOFacade offlineDAOFacade;

  private static boolean isWordCharacter(String word) {
    return WORD_CHARACTER_PATTERN.matcher(word).matches();
  }

  @Autowired
  public void setOfflineManager(OfflineManager offlineManager) {
    this.offlineManager = offlineManager;
  }

  public void doTestTransaction(Context context) {
    WorkFlow wf = new WorkFlow();
    wf.setCreateTime(new Date());
    wf.setGitPath("gitpath");
    wf.setName("baisuitest");
    wf.setOpUserId(123);
    wf.setOpUserName("baisui");
    wf.setOpTime(new Date());
    this.offlineDAOFacade.getWorkFlowDAO().insertSelective(wf);
  }

  /**
   * @param context
   * @throws Exception
   */
  public void doGetWorkflowId(Context context) throws Exception {
    AppDomainInfo app = this.getAppDomain();
    if (app.getApp().getWorkFlowId() == null) {
      throw new IllegalStateException("WorkFlowId have not been set in collection:" + app.getAppName());
    }
    Map<String, Integer> result = Maps.newHashMap();
    result.put("workflowId", app.getApp().getWorkFlowId());
    this.setBizResult(context, result);
  }

  /**
   * ??????????????????DB??????????????????
   *
   * @param context
   */
  @Func(value = PermissionConstant.PERMISSION_DATASOURCE_EDIT, sideEffect = false)
  public void doSelectDbChange(Context context) throws Exception {
    Integer dbid = this.getInt("dbid");
    com.qlangtech.tis.workflow.pojo.DatasourceDb db = this.offlineDAOFacade.getDatasourceDbDAO().selectByPrimaryKey(dbid);
    IPluginStore<DataSourceFactory> dbPlugin = TIS.getDataBasePluginStore(new PostedDSProp(db.getName(), DbScope.DETAILED));

    List<String> tabs = dbPlugin.getPlugin().getTablesInDB();
    // ??????DB???????????????????????????db??????????????????
    // ?????????tabs??????
    this.setBizResult(context, tabs.stream().map((t) -> {
      return new Option(t, t);
    }).collect(Collectors.toList()));
  }


  private class DbEnumShow implements Comparable<DbEnumShow> {

    private String dbName;

    private String host;

    DbEnumShow(String dbName, String host) {
      this.dbName = dbName;
      this.host = host;
    }

    public String getDbName() {
      return dbName;
    }

    public String getHost() {
      return host;
    }

    @Override
    public int compareTo(DbEnumShow o) {
      String dbName = this.getDbName();
      String anotherDbName = o.getDbName();
      if (dbName.length() == anotherDbName.length()) {
        return this.getDbName().compareTo(o.getDbName());
      } else {
        return dbName.length() - anotherDbName.length();
      }
    }
  }

  /**
   * Do getUsableDbNames dbs. ????????????????????????????????????
   *
   * @param context the context
   */
  public void doGetUsableDbNames(Context context) {
    this.setBizResult(context, offlineManager.getUsableDbNames());
  }


  private void addPasswordValidator(boolean isNew, TISDb pojo, Map<String, Validator.FieldValidators> validateRule) {
    Validator[] validators = isNew ? new Validator[]{Validator.require} : new Validator[0];
    validateRule.put("password", new Validator.FieldValidators(validators) {

      @Override
      public void setFieldVal(String val) {
        pojo.setPassword(val);
      }
    });
  }

  private TISTable getTablePojo(Context context) {
    com.alibaba.fastjson.JSONObject form = this.parseJsonPost();
    final boolean updateMode = !form.getBoolean("isAdd");
    String tableName = form.getString("tableName");
    if (StringUtils.isBlank(tableName)) {
      this.addErrorMessage(context, "??????????????????");
      return null;
    }
    if (!isWordCharacter(tableName)) {
      this.addErrorMessage(context, "??????????????????????????????????????????????????????");
      return null;
    }
    String tableLogicName = tableName;
    Integer partitionNum = form.getIntValue("partitionNum");
    if (partitionNum == null) {
      this.addErrorMessage(context, "?????????????????????");
      return null;
    }
    Integer dbId = form.getIntValue("dbId");
    if (dbId == null) {
      this.addErrorMessage(context, "?????????????????????");
      return null;
    }
    Integer partitionInterval = form.getIntValue("partitionInterval");
    if (partitionInterval == null) {
      this.addErrorMessage(context, "????????????????????????");
      return null;
    }
    String selectSql = form.getString("selectSql");
    if (StringUtils.isBlank(selectSql) || StringUtils.contains(selectSql, "*")) {
      this.addErrorMessage(context, "sql?????????????????????????????????*");
      return null;
    }
    TISTable tab = new TISTable(tableName, partitionNum, dbId, partitionInterval, selectSql);
    com.alibaba.fastjson.JSONArray cols = form.getJSONArray("cols");
    com.alibaba.fastjson.JSONObject col = null;
    ColumnMetaData colMeta = null;
    for (int i = 0; i < cols.size(); i++) {
      col = cols.getJSONObject(i);
      colMeta = new ColumnMetaData(i, col.getString("key"), new ColumnMetaData.DataType(col.getIntValue("type")), col.getBoolean("pk"));
      // tab.addColumnMeta(colMeta);
    }
    if (updateMode) {
      tab.setTabId(form.getInteger("tabId"));
    }
    tab.setDbName(offlineDAOFacade.getDatasourceDbDAO().selectByPrimaryKey(dbId).getName());
    return tab;
  }

  /**
   * @param context
   * @throws Exception
   */
  @Func(value = PermissionConstant.PERMISSION_DATASOURCE_EDIT)
  public void doAddDatasourceTable(Context context) throws Exception {
    this.errorsPageShow(context);
    // 1. ???????????????
    TISTable pojo = getTablePojo(context);
    if (pojo == null) {
      return;
    }
    // 2. ?????????
    offlineManager.addDatasourceTable(pojo, this, this, context, pojo.getTabId() != null, false);
  }

  // /**
  // * ??????table??????
  // *
  // * @param context
  // * @throws Exception
  // */
  // public void doEditDatasourceTable(Context context) throws Exception {
  // // 1. ????????????????????????
  // TISTable pojo = getTablePojo(context);
  // if (pojo == null) {
  // return;
  // }
  // Integer tableId = this.getInt("id");
  // if (tableId == null) {
  // this.addErrorMessage(context, "table id ????????????");
  // return;
  // }
  // // 3. ??????git
  // offlineManager.editDatasourceTable(pojo, this, context);
  // // 4. ????????????????????????dump????????????
  // offlineManager.disableTableDump(tableId, this, context);
  // this.addActionMessage(context, "table????????????");
  // }

  /**
   * Do get git commit logs. ?????????????????????????????????
   *
   * @param context the context
   * @throws Exception the exception
   */
  public void doGetGitCommitLogs(Context context) throws Exception {
    String directory = this.getString("directory");
    int projectId;
    if (StringUtils.equals("datasource_daily", directory)) {
      projectId = GitUtils.DATASOURCE_PROJECT_ID;
    } else if (StringUtils.equals("datasource_online", directory)) {
      projectId = GitUtils.DATASOURCE_PROJECT_ID;
    } else if (StringUtils.equals("workflow", directory)) {
      projectId = GitUtils.WORKFLOW_GIT_PROJECT_ID;
    } else {
      throw new RuntimeException("directory = " + directory + " is wrong!");
    }
    List<GitRepositoryCommitPojo> commits = GitUtils.$().getGitRepositoryCommits(projectId);
    while (commits.size() > COMMITS_LIMIT) {
      commits.remove(commits.size() - 1);
    }
    this.setBizResult(context, commits);
  }

  // /**
  // * Do get commit version diff. ?????????????????????????????????
  // *
  // * @param context the context
  // * @throws Exception the exception
  // */
  // public void doGetCommitVersionDiff(Context context) throws Exception {
  // String fromVersion = this.getString("fromVersion");
  // String toVersion = this.getString("toVersion");
  // if (StringUtils.isBlank(fromVersion) || fromVersion.length() != 40) {
  // this.addErrorMessage(context, "fromVersion???????????????");
  // return;
  // }
  // if (StringUtils.isBlank(toVersion) || toVersion.length() != 40) {
  // this.addErrorMessage(context, "toVersion???????????????");
  // return;
  // }
  // String directory = this.getString("directory");
  // int projectId;
  // if (StringUtils.equals("datasource_daily", directory)) {
  // projectId = GitUtils.DATASOURCE_PROJECT_ID;
  // } else if (StringUtils.equals("datasource_online", directory)) {
  // projectId = GitUtils.DATASOURCE_PROJECT_ID;
  // } else if (StringUtils.equals("workflow", directory)) {
  // projectId = GitUtils.WORKFLOW_GIT_PROJECT_ID;
  // } else {
  // throw new RuntimeException("directory = " + directory + " is wrong!");
  // }
  // GitCommitVersionDiff diff = GitUtils.$().getGitCommitVersionDiff(fromVersion, toVersion, projectId);
  // this.setBizResult(context, diff);
  // }

  /**
   * Do get workflows. ???????????????????????????????????????
   *
   * @param context the context
   * @throws Exception the exception
   */
  public void doGetWorkflows(Context context) throws Exception {
    Pager pager = createPager();
    IWorkFlowDAO wfDAO = this.getWorkflowDAOFacade().getWorkFlowDAO();
    WorkFlowCriteria query = new WorkFlowCriteria();
    query.createCriteria();
    query.setOrderByClause("id desc");
    pager.setTotalCount(wfDAO.countByExample(query));
    this.setBizResult(context, new PaginationResult(pager, wfDAO.selectByExample(query, pager.getCurPage(), pager.getRowsPerPage())));
  }

  public void doGetWorkflowTopology(Context context) throws Exception {
    final String topology = this.getString("topology");
    if (StringUtils.isEmpty(topology)) {
      throw new IllegalStateException("please set param topology");
    }
    SqlDataFlowTopology wfTopology = SqlTaskNodeMeta.getSqlDataFlowTopology(topology);
    this.setBizResult(context, wfTopology);
  }

  /**
   * ?????????????????????erRules
   *
   * @param context
   * @throws Exception
   */
  public void doGetWorkflowTopologyERRule(Context context) throws Exception {
    final String topology = this.getString("topology");
    if (StringUtils.isEmpty(topology)) {
      throw new IllegalStateException("please set param topology");
    }
    SqlDataFlowTopology wfTopology = SqlTaskNodeMeta.getSqlDataFlowTopology(topology);
    this.setBizResult(context, wfTopology.getDumpNodes());
  }

  @Func(PermissionConstant.DATAFLOW_UPDATE)
  public void doUpdateTopology(Context context) throws Exception {

    this.doUpdateTopology(context, new TopologyUpdateCallback() {
      @Override
      public <T> T execute(String topologyName, SqlDataFlowTopology topology) {
        SqlTaskNodeMeta.TopologyProfile profile = topology.getProfile();
        if (profile.getDataflowId() < 1) {
          profile.setDataflowId(getWorkflowId(topologyName));
        }
        return null;
      }
    });

//    this.doUpdateTopology(context, (topologyName, topology) -> {
//      SqlTaskNodeMeta.TopologyProfile profile = topology.getProfile();
//      if (profile.getDataflowId() < 1) {
//        profile.setDataflowId(this.getWorkflowId(topologyName));
//      }
//    });
  }

  /**
   * WorkflowAddJoinComponent ????????????????????????????????????????????????
   *
   * @param context
   * @throws Exception
   */
  @Func(value = PermissionConstant.DATAFLOW_MANAGE, sideEffect = false)
  public void doValidateWorkflowAddJoinComponentForm(Context context) throws Exception {
    com.alibaba.fastjson.JSONObject form = this.parseJsonPost();

    IControlMsgHandler handler = new DelegateControl4JsonPostMsgHandler(this, form);

    String sql = form.getString("sql");
    String exportName = form.getString("exportName");
    com.alibaba.fastjson.JSONArray dependenceNodes = form.getJSONArray("dependencies");
    final List<String> dependencyNodes = Lists.newArrayList();
    final String validateRuleDependency = "dependencies";
    Map<String, Validator.FieldValidators> validateRule = //
      Validator.fieldsValidator(//
        "sql" //
        , new Validator.FieldValidators(Validator.require) {
          // ??????????????????
        }.addDependency(validateRuleDependency), //
        new Validator.IFieldValidator() {
          @Override
          public boolean validate(IFieldErrorHandler msgHandler, Context context, String fieldKey, String fieldData) {
            Optional<TisSqlFormatException> sqlErr = SqlTaskNodeMeta.validateSql(fieldData, dependencyNodes);
            if (sqlErr.isPresent()) {
              msgHandler.addFieldError(context, fieldKey, sqlErr.get().summary());
              return false;
            }
            return true;
          }
        }, //
        "exportName" //
        , new Validator.FieldValidators(Validator.require, Validator.identity
        ) {
        },
        new Validator.IFieldValidator() {
          @Override
          public boolean validate(IFieldErrorHandler msgHandler, Context context, String fieldKey, String fieldData) {
            Matcher m = pattern_table_name.matcher(fieldData);
            if (!m.matches()) {
              msgHandler.addFieldError(context, fieldKey, "??????????????????:" + pattern_table_name);
              return false;
            }
            return true;
          }
        }//
        , validateRuleDependency //
        , new Validator.FieldValidators(Validator.require) {
          @Override
          public void setFieldVal(String val) {
            com.alibaba.fastjson.JSONArray dpts = JSON.parseArray(val);
            com.alibaba.fastjson.JSONObject o = null;
            for (int index = 0; index < dpts.size(); index++) {
              o = dpts.getJSONObject(index);
              dependencyNodes.add(o.getString("label"));
            }
          }
        },
        new Validator.IFieldValidator() {
          @Override
          public boolean validate(IFieldErrorHandler msgHandler, Context context, String fieldKey, String fieldData) {
            com.alibaba.fastjson.JSONArray dpts = JSON.parseArray(fieldData);
            if (dpts.size() < 1) {
              msgHandler.addFieldError(context, fieldKey, "????????????????????????");
              return false;
            }
            return true;
          }
        });

    if (!Validator.validate(handler, context, validateRule)) {
      // return;
    }

  }

  private void doUpdateTopology(Context context, TopologyUpdateCallback dbSaver) throws Exception {
    final String content = IOUtils.toString(this.getRequest().getInputStream(), getEncode());
    JSONTokener tokener = new JSONTokener(content);
    JSONObject topology = new JSONObject(tokener);
    final String topologyName = topology.getString("topologyName");
    if (StringUtils.isEmpty(topologyName)) {
      this.addErrorMessage(context, "????????????????????????");
      return;
    }
    File parent = new File(SqlTaskNode.parent, topologyName);
    FileUtils.forceMkdir(parent);
    JSONArray nodes = topology.getJSONArray("nodes");
    // ?????????????????????????????????????????????????????????nodes?????????
    JSONArray edges = topology.getJSONArray("edges");
    JSONObject o = null;
    JSONObject nodeMeta = null;
    JSONObject nestNodeMeta = null;
    JSONArray joinDependencies = null;
    JSONObject dep = null;
    NodeType nodetype = null;
    DependencyNode dnode = null;
    SqlTaskNodeMeta pnode = null;
    Position pos = null;
    final SqlDataFlowTopology topologyPojo = new SqlDataFlowTopology();
    SqlTaskNodeMeta.TopologyProfile profile = new SqlTaskNodeMeta.TopologyProfile();
    profile.setName(topologyName);
    profile.setTimestamp(System.currentTimeMillis());
    // profile.setDataflowId(this.getWorkflowId(topologyName));
    topologyPojo.setProfile(profile);
    int x, y;
    int tableid;
    Tab tab;
    for (int i = 0; i < nodes.length(); i++) {
      o = nodes.getJSONObject(i);
      x = o.getInt("x");
      if (x < 0) {
        // ???????????????????????????????????????`
        continue;
      }
      y = o.getInt("y");
      pos = new Position();
      pos.setX(x);
      pos.setY(y);
      nodeMeta = o.getJSONObject("nodeMeta");
      nestNodeMeta = nodeMeta.getJSONObject("nodeMeta");
      nodetype = NodeType.parse(nestNodeMeta.getString("type"));

      if (nodetype == NodeType.DUMP) {
        dnode = new DependencyNode();
        dnode.setExtraSql(SqlTaskNodeMeta.processBigContent(nodeMeta.getString("sqlcontent")));
        dnode.setId(o.getString("id"));
        tableid = nodeMeta.getInt("tabid");
        Map<Integer, com.qlangtech.tis.workflow.pojo.DatasourceDb> dbMap = Maps.newHashMap();
        tab = getDatabase(this.offlineDAOFacade, dbMap, tableid);
        dnode.setDbName(tab.db.getName());
        dnode.setName(tab.tab.getName());
        dnode.setTabid(String.valueOf(tableid));
        dnode.setDbid(String.valueOf(nodeMeta.get("dbid")));
        dnode.setPosition(pos);
        dnode.setType(NodeType.DUMP.getType());
        topologyPojo.addDumpTab(dnode);
      } else if (nodetype == NodeType.JOINER_SQL) {
        pnode = new SqlTaskNodeMeta();
        pnode.setId(o.getString("id"));
        pnode.setPosition(pos);
        pnode.setSql(SqlTaskNodeMeta.processBigContent(nodeMeta.getString("sql")));
        pnode.setExportName(nodeMeta.getString("exportName"));
        // pnode.setId(String.valueOf(nodeMeta.get("id")));
        //pnode.setId(o.getString("id"));
        pnode.setType(NodeType.JOINER_SQL.getType());
        joinDependencies = nodeMeta.getJSONArray("dependencies");
        for (int k = 0; k < joinDependencies.length(); k++) {
          dep = joinDependencies.getJSONObject(k);
          dnode = new DependencyNode();
          dnode.setId(dep.getString("value"));
          dnode.setName(dep.getString("label"));
          dnode.setType(NodeType.DUMP.getType());
          pnode.addDependency(dnode);
        }
        topologyPojo.addNodeMeta(pnode);
      } else {
        throw new IllegalStateException("nodetype:" + nodetype + " is illegal");
      }
    }
    // ????????????????????????????????????????????????
    List<SqlTaskNodeMeta> finalNodes = topologyPojo.getFinalNodes();
    if (finalNodes.size() > 1) {
      this.addErrorMessage(context, "??????????????????(" + finalNodes.stream()
        .map((r) -> r.getExportName()).collect(Collectors.joining(",")) + ")??????????????????");
      return;
    }
    if (finalNodes.size() < 1) {
      // ???????????????????????????????????????spark???hive?????????
      // this.addErrorMessage(context, "???????????????????????????");
      // return;

    }
    Optional<ERRules> erRule = ERRules.getErRule(topologyPojo.getName());
    this.setBizResult(context, new ERRulesStatus(erRule));
    dbSaver.execute(topologyName, topologyPojo);
    // ?????????????????????
    SqlTaskNodeMeta.persistence(topologyPojo, parent);
    // ????????????
    FileUtils.write(new File(parent, topologyName + "_content.json"), content, getEncode(), false);
    this.addActionMessage(context, "'" + topologyName + "'????????????");
  }

  public static class ERRulesStatus {

    private final ERRules erRules;

    private boolean exist;

    public ERRulesStatus(Optional<ERRules> erRules) {
      if (this.exist = erRules.isPresent()) {
        this.erRules = erRules.get();
      } else {
        this.erRules = null;
      }
    }

    public boolean isErExist() {
      return this.exist;
    }

    public boolean isErPrimaryTabSet() {
      if (!isErExist()) {
        return false;
      }
      return this.erRules.getPrimaryTabs().size() > 0;
    }
  }

  private Integer getWorkflowId(String topologyName) {
    // return SqlTaskNodeMeta.getTopologyProfile(topologyName).getDataflowId();
    WorkFlowCriteria wc = new WorkFlowCriteria();
    wc.createCriteria().andNameEqualTo(topologyName);
    List<WorkFlow> workFlows = this.getWorkflowDAOFacade().getWorkFlowDAO().selectByExample(wc);
    for (WorkFlow wf : workFlows) {
      // topologyPojo.setDataflowId(wf.getId());
      return wf.getId();
    }
    throw new IllegalStateException("topology:" + topologyName + " can not find workflow record in db");
  }

  public static Tab getDatabase(IWorkflowDAOFacade wfDaoFacade, Map<Integer, com.qlangtech.tis.workflow.pojo.DatasourceDb> dbMap, int tableid) {
    Tab dtab = null;
    DatasourceTable tab = wfDaoFacade.getDatasourceTableDAO().selectByPrimaryKey(tableid);
    if (tab == null) {
      throw new IllegalStateException("tabid:" + tableid + " relevant 'TableDump' object can not be null");
    }
    dtab = new Tab(tab);
    com.qlangtech.tis.workflow.pojo.DatasourceDb db = null;
    if ((db = dbMap.get(tab.getDbId())) == null) {
      db = wfDaoFacade.getDatasourceDbDAO().selectByPrimaryKey(tab.getDbId());
      if (db == null) {
        throw new IllegalStateException("tabid:" + tableid + " relevant 'TableDump' object can not be null");
      }
      dbMap.put(tab.getDbId(), db);
    }
    dtab.setDb(db);
    return dtab;
  }

  private static class Tab {

    private com.qlangtech.tis.workflow.pojo.DatasourceDb db;

    private final DatasourceTable tab;

    public Tab(DatasourceTable tab) {
      super();
      this.tab = tab;
    }

    public com.qlangtech.tis.workflow.pojo.DatasourceDb getDb() {
      return db;
    }

    public void setDb(com.qlangtech.tis.workflow.pojo.DatasourceDb db) {
      this.db = db;
    }

    public DatasourceTable getTab() {
      return tab;
    }
  }

  /**
   * ??????ER????????????
   *
   * @param context
   * @throws Exception
   */
  public void doGetErRule(Context context) throws Exception {
    String topology = this.getString("topology");
    // ????????????,???????????????toplolog????????????????????????????????????ER???????????????topology??????????????????????????????
    boolean forceSync = this.getBoolean("sync");
    if (StringUtils.isEmpty(topology)) {
      throw new IllegalArgumentException("param 'topology' can not be null");
    }
    ERRules erRule = null;
    if (!ERRules.ruleExist(topology)) {
      // ???????????????ER??????
      erRule = new ERRules();
      for (DependencyNode node : getDumpNodesFromTopology(topology)) {
        erRule.addDumpNode(node);
      }
    } else {
      erRule = ERRules.getErRule(topology).get();
      final List<DependencyNode> oldErRules = erRule.getDumpNodes();
      if (forceSync) {
        List<DependencyNode> dumpNodes = getDumpNodesFromTopology(topology);
        /**
         * *************************************
         * ?????????????????????
         * ***************************************
         */
        List<DependencyNode> shallBeAdd = dumpNodes.stream().filter((n) -> !oldErRules.stream().filter((old) -> StringUtils.equals(n.getTabid(), old.getTabid())).findAny().isPresent()).collect(Collectors.toList());
        /**
         * *************************************
         * ?????????????????????
         * ***************************************
         */
        Iterator<DependencyNode> it = oldErRules.iterator();
        while (it.hasNext()) {
          DependencyNode old = it.next();
          if (!dumpNodes.stream().filter((n) -> StringUtils.equals(n.getTabid(), old.getTabid())).findAny().isPresent()) {
            it.remove();
          }
        }
        oldErRules.addAll(shallBeAdd);
      }
    }
    this.setBizResult(context, erRule);
  }

  private List<DependencyNode> getDumpNodesFromTopology(String topology) throws Exception {
    SqlDataFlowTopology wfTopology = SqlTaskNodeMeta.getSqlDataFlowTopology(topology);
    return wfTopology.getDumpNodes();
  }

  /**
   * ??????ER??????
   *
   * @param context
   * @throws Exception
   */
  @Func(value = PermissionConstant.DATAFLOW_UPDATE)
  public void doSaveErRule(Context context) throws Exception {
    com.alibaba.fastjson.JSONObject j = this.parseJsonPost();
    ERRules erRules = new ERRules();
    final String topology = j.getString("topologyName");
    if (StringUtils.isEmpty(topology)) {
      throw new IllegalArgumentException("param 'topology' can not be empty");
    }
    SqlDataFlowTopology df = SqlTaskNodeMeta.getSqlDataFlowTopology(topology);
    com.alibaba.fastjson.JSONArray edges = j.getJSONArray("edges");
    com.alibaba.fastjson.JSONArray nodes = j.getJSONArray("nodes");
    com.alibaba.fastjson.JSONObject edge = null;
    com.alibaba.fastjson.JSONObject sourceNode = null;
    com.alibaba.fastjson.JSONObject targetNode = null;
    com.alibaba.fastjson.JSONObject linkrule = null;
    com.alibaba.fastjson.JSONArray linkKeyList = null;
    com.alibaba.fastjson.JSONObject link = null;
    com.alibaba.fastjson.JSONObject nodeTuple = null;
    com.alibaba.fastjson.JSONObject node = null;
    com.alibaba.fastjson.JSONObject ermeta = null;
    TableRelation erRelation = null;
    for (int i = 0; i < edges.size(); i++) {
      edge = edges.getJSONObject(i);
      sourceNode = edge.getJSONObject("sourceNode");
      // getTableName(sourceNode);
      targetNode = edge.getJSONObject("targetNode");
      // getTableName(targetNode);
      linkrule = edge.getJSONObject("linkrule");
      if (linkrule == null) {
        throw new IllegalStateException("linkrule can not be null");
      }
      erRelation = $(edge.getString("id"), df, getTableName(targetNode), getTableName(sourceNode), TabCardinality.parse(linkrule.getString("cardinality")));
      linkKeyList = linkrule.getJSONArray("linkKeyList");
      for (int jj = 0; jj < linkKeyList.size(); jj++) {
        link = linkKeyList.getJSONObject(jj);
        erRelation.addJoinerKey(link.getString("parentKey"), link.getString("childKey"));
      }
      erRules.addRelation(erRelation);
    }
    com.alibaba.fastjson.JSONObject nodeMeta = null;
    com.alibaba.fastjson.JSONObject colTransfer = null;
    DependencyNode dumpNode = null;
    com.alibaba.fastjson.JSONArray columnTransferList = null;
    TabExtraMeta tabMeta = null;
    String sharedKey = null;
    for (int index = 0; index < nodes.size(); index++) {
      nodeTuple = nodes.getJSONObject(index);
      node = nodeTuple.getJSONObject("node");
      ermeta = node.getJSONObject("extraMeta");
      dumpNode = new DependencyNode();
      if (ermeta != null) {
        tabMeta = new TabExtraMeta();
        tabMeta.setPrimaryIndexTab(ermeta.getBoolean("primaryIndexTab"));
        if (tabMeta.isPrimaryIndexTab()) {
          sharedKey = ermeta.getString("sharedKey");
          tabMeta.setSharedKey(sharedKey);
          com.alibaba.fastjson.JSONArray primaryIndexColumnNames = ermeta.getJSONArray("primaryIndexColumnNames");
          com.alibaba.fastjson.JSONObject primaryIndex = null;
          List<PrimaryLinkKey> names = Lists.newArrayList();
          PrimaryLinkKey plinkKey = null;
          for (int i = 0; i < primaryIndexColumnNames.size(); i++) {
            primaryIndex = primaryIndexColumnNames.getJSONObject(i);
            plinkKey = new PrimaryLinkKey();
            plinkKey.setName(primaryIndex.getString("name"));
            plinkKey.setPk(primaryIndex.getBoolean("pk"));
            names.add(plinkKey);
          }
          tabMeta.setPrimaryIndexColumnNames(names);
        }
        tabMeta.setMonitorTrigger(ermeta.getBoolean("monitorTrigger"));
        tabMeta.setTimeVerColName(null);
        if (tabMeta.isMonitorTrigger()) {
          tabMeta.setTimeVerColName(ermeta.getString("timeVerColName"));
        }
        columnTransferList = ermeta.getJSONArray("columnTransferList");
        for (int i = 0; i < columnTransferList.size(); i++) {
          colTransfer = columnTransferList.getJSONObject(i);
          tabMeta.addColumnTransfer(new ColumnTransfer(colTransfer.getString("colKey"), colTransfer.getString("transfer"), colTransfer.getString("param")));
        }
        dumpNode.setExtraMeta(tabMeta);
      }
      dumpNode.setId(node.getString("id"));
      nodeMeta = node.getJSONObject("nodeMeta");
      dumpNode.setTabid(nodeMeta.getString("tabid"));
      dumpNode.setDbid(nodeMeta.getString("dbid"));
      Position pos = new Position();
      pos.setX(node.getIntValue("x"));
      pos.setY(node.getIntValue("y"));
      dumpNode.setPosition(pos);
      dumpNode.setName(nodeMeta.getString("tabname"));
      // dumpNode.setExtraSql(nodeMeta.getString("sqlcontent"));
      erRules.addDumpNode(dumpNode);
    }
    List<PrimaryTableMeta> primaryTabs = erRules.getPrimaryTabs();
    if (primaryTabs.size() < 1) {
      this.addErrorMessage(context, "???????????????ER????????????");
      return;
    }
    List<PrimaryLinkKey> pkNames = null;
    for (PrimaryTableMeta meta : primaryTabs) {
      if (StringUtils.isEmpty(meta.getSharedKey())) {
        this.addErrorMessage(context, "????????????:" + meta.getTabName() + " ?????????????????????");
      }
      pkNames = meta.getPrimaryKeyNames();
      if (pkNames.size() < 1) {
        this.addErrorMessage(context, "????????????:" + meta.getTabName() + " ??????????????????");
      }
    }
    if (this.hasErrors(context)) {
      return;
    }
//    File parent = new File(SqlTaskNode.parent, topology);
//    FileUtils.forceMkdir(parent);
//    FileUtils.write(new File(parent, ERRules.ER_RULES_FILE_NAME), ERRules.serialize(erRules), getEncode(), false);

    ERRules.write(topology, erRules);

    // System.out.println(j.toJSONString());
    long wfId = df.getProfile().getDataflowId();
    if (wfId < 1) {
      throw new IllegalStateException("topology '" + topology + "' relevant wfid can not be null");
    }
    WorkFlow wf = new WorkFlow();
    wf.setOpTime(new Date());
    WorkFlowCriteria wfCriteria = new WorkFlowCriteria();
    wfCriteria.createCriteria().andIdEqualTo((int) wfId);
    this.getWorkflowDAOFacade().getWorkFlowDAO().updateByExampleSelective(wf, wfCriteria);
  }

  private String getTableName(com.alibaba.fastjson.JSONObject sourceNode) {
    com.alibaba.fastjson.JSONObject nodeMeta = sourceNode.getJSONObject("nodeMeta");
    return nodeMeta.getString("tabname");
  }

  /**
   * ?????? ?????????
   *
   * @param context
   * @throws Exception
   */
  @Func(value = PermissionConstant.DATAFLOW_UPDATE)
  public void doSaveTopology(Context context) throws Exception {
    this.doUpdateTopology(context, new CreateTopologyUpdateCallback(this.getUser(), this.getWorkflowDAOFacade()));
  }

  public static class CreateTopologyUpdateCallback implements TopologyUpdateCallback {
    private final IUser user;
    private final IWorkflowDAOFacade offlineDAOFacade;
    private final boolean idempotent;

    public CreateTopologyUpdateCallback(IUser user, IWorkflowDAOFacade offlineDAOFacade) {
      this(user, offlineDAOFacade, false);
    }

    public CreateTopologyUpdateCallback(IUser user, IWorkflowDAOFacade offlineDAOFacade, boolean idempotent) {
      this.user = user;
      this.offlineDAOFacade = offlineDAOFacade;
      this.idempotent = idempotent;
    }

    @Override
    public <T> T execute(String tname, SqlDataFlowTopology topology) {
      final String topologyName = topology.getName();
      if (idempotent) {
        WorkFlowCriteria wfCriteria = new WorkFlowCriteria();
        wfCriteria.createCriteria().andNameEqualTo(topologyName);
        for (WorkFlow wf : offlineDAOFacade.getWorkFlowDAO().selectByExample(wfCriteria)) {
          topology.getProfile().setDataflowId(wf.getId());
          return (T) wf;
        }
      }


      WorkFlow workFlow = new WorkFlow();
      workFlow.setName(topologyName);
      //  IUser user = getUser();
      workFlow.setOpUserId(1);
      workFlow.setOpUserName(user.getName());
      workFlow.setGitPath(String.valueOf(topology.getTimestamp()));
      workFlow.setCreateTime(new Date());
      workFlow.setInChange(new Byte("1"));
      Integer dfId = offlineDAOFacade.getWorkFlowDAO().insertSelective(workFlow);
      topology.getProfile().setDataflowId(dfId);
      workFlow.setId(dfId);
      return (T) workFlow;
    }
  }

  private interface TopologyUpdateCallback {

    <T> T execute(String topologyName, SqlDataFlowTopology topology);
  }

  /**
   * Do get datasource tables. ????????????????????????????????????
   *
   * @param context the context
   * @throws Exception the exception
   */
  public void doGetDatasourceTables(Context context) throws Exception {
    this.setBizResult(context, offlineManager.getDatasourceTables());
  }

  /**
   * Do edit workflow. ???????????????
   *
   * @param context the context
   * @throws Exception the exception
   */
  public void doEditWorkflow(Context context) throws Exception {
    WorkflowPojo pojo = getWorkflowPojo(context);
    if (pojo == null) {
      return;
    }
    offlineManager.editWorkflow(pojo, this, context);
  }

  /**
   * description: ?????????????????????
   */
  public void doDeleteWorkflow(Context context) throws Exception {
    Integer id = this.getInt("id");
    if (id == null) {
      this.addErrorMessage(context, "workflow id ????????????");
      return;
    }
    offlineManager.deleteWorkflow(id, this, context);
    this.doGetWorkflows(context);
  }

  private WorkflowPojo getWorkflowPojo(Context context) {
    String name = this.getString("workflowName");
    if (StringUtils.isBlank(name)) {
      this.addErrorMessage(context, "????????????????????????");
      return null;
    }
    if (!isWordCharacter(name)) {
      this.addErrorMessage(context, "????????????????????????????????????????????????????????????");
      return null;
    }
    String taskScript = this.getString("taskScript");
    if (StringUtils.isBlank(taskScript)) {
      this.addErrorMessage(context, "????????????????????????");
      return null;
    }
    return new WorkflowPojo(name, new JoinRule(taskScript));
  }


  public static List<Option> existDbs = null;


  /**
   * Do get datasource info. ??????????????????????????????
   *
   * @param context the context
   * @throws Exception the exception
   */
  public void doGetDatasourceInfo(Context context) throws Exception {
    this.setBizResult(context
      , new ConfigDsMeta(offlineManager.getDatasourceInfo(), TIS.get().getDescriptorList(DataSourceFactory.class)));
  }


  public static class ConfigDsMeta extends PluginDescMeta {
    private final Collection<OfflineDatasourceAction.DatasourceDb> dbs;

    public ConfigDsMeta(Collection<DatasourceDb> dbs, DescriptorExtensionList<DataSourceFactory, Descriptor<DataSourceFactory>> descList) {
      super(descList);
      this.dbs = dbs;
    }

    public Collection<DatasourceDb> getDbs() {
      return dbs;
    }
  }


  /**
   * Do get datasource db. ????????????db???git????????????
   *
   * @param context the context
   */
  public void doGetDatasourceDbById(Context context) {
    Integer dbId = this.getInt("id");
    DBConfigSuit configSuit = offlineManager.getDbConfig(this, dbId);
    this.setBizResult(context, configSuit);
  }

  /**
   * ???????????????,?????????????????????
   *
   * @param context
   * @throws IOException
   */
  public void doGetDsTabsVals(Context context) throws IOException {
    com.alibaba.fastjson.JSONObject body = this.parseJsonPost();
    com.alibaba.fastjson.JSONArray tabs = body.getJSONArray("tabs");
    if (tabs == null) {
      throw new IllegalArgumentException("initialize Tabs can not be null");
    }
    List<String> selectedTabs
      = tabs.stream().map((tab) -> (String) tab).collect(Collectors.toList());

    String pluginName = body.getString("name");
    boolean require = body.getBooleanValue("require");
    String extraParam = body.getString("extraParam");

    UploadPluginMeta pluginMeta = null;
    List<UploadPluginMeta> pluginMetas
      = UploadPluginMeta.parse(new String[]{pluginName + ":" + (require ? "require" : StringUtils.EMPTY) + "," + extraParam});
    for (UploadPluginMeta m : pluginMetas) {
      pluginMeta = m;
    }

    Objects.requireNonNull(pluginMeta, "pluginMeta can not be null");

    HeteroList<DataxReader> heteroList = pluginMeta.getHeteroList(this);
    List<DataxReader> readers = heteroList.getItems();
    Map<String, List<ColumnMetaData>> mapCols = null;
    List<ISelectedTab> allNewTabs = Lists.newArrayList();
    PluginFormProperties pluginFormPropertyTypes = null;
    Map<String, Object> bizResult = Maps.newHashMap();
    for (DataxReader reader : readers) {
      mapCols = selectedTabs.stream().collect(Collectors.toMap((tab) -> tab, (tab) -> {
        return reader.getTableMetadata(tab);
      }));
      if (MapUtils.isEmpty(mapCols)) {
        throw new IllegalStateException("mapCols can not be empty");
      }
      pluginFormPropertyTypes = reader.getDescriptor().getPluginFormPropertyTypes(pluginMeta.getSubFormFilter());
      for (Map.Entry<String, List<ColumnMetaData>> tab2cols : mapCols.entrySet()) {
        allNewTabs.add(createNewSelectedTab(pluginFormPropertyTypes, tab2cols));
      }

      DescriptorsJSON desc2Json = new DescriptorsJSON(reader.getDescriptor());
      bizResult.put("subformDescriptor", desc2Json.getDescriptorsJSON(pluginMeta.getSubFormFilter()));
      break;
    }

    Objects.requireNonNull(pluginFormPropertyTypes, "pluginFormPropertyTypes can not be null");
    if (allNewTabs.size() < 1) {
      throw new IllegalStateException("allNewTabs size can not small than 1");
    }

    bizResult.put("tabVals", pluginFormPropertyTypes.accept(new PluginFormProperties.IVisitor() {
      @Override
      public com.alibaba.fastjson.JSONObject visit(SuFormProperties props) {
        return props.createSubFormVals(allNewTabs);
      }
    }));

    this.setBizResult(context, bizResult);
  }

  /**
   * ???????????????????????????tab???????????????SelectedTab?????????????????????????????????????????????????????????????????????????????????
   *
   * @param pluginFormPropertyTypes
   * @param tab2cols
   * @return
   */
  private ISelectedTab createNewSelectedTab(
    PluginFormProperties pluginFormPropertyTypes, Map.Entry<String, List<ColumnMetaData>> tab2cols) {
    return pluginFormPropertyTypes.accept(new PluginFormProperties.IVisitor() {
      @Override
      public ISelectedTab visit(SuFormProperties props) {

//            try {
//            } catch (Exception e) {
//              throw new RuntimeException("create subform,table:" + tab2cols.getKey()
//                + ",cols size:" + tab2cols.getValue().size(), e);
//            }

        ISelectedTab subForm = props.newSubDetailed();
        PropertyType pp = null;
        for (Map.Entry<String, PropertyType> pentry : props.getKVTuples()) {
          pp = pentry.getValue();
          if (pp.isIdentity()) {
            pp.setVal(subForm, tab2cols.getKey());
            continue;
          }
          if (pp.formField.type() == FormFieldType.MULTI_SELECTABLE) {
            pp.setVal(subForm
              , tab2cols.getValue().stream().map((c) -> c.getName()).collect(Collectors.toList()));
            continue;
          }
          if (pp.isInputRequired()) {
            if (StringUtils.isNotEmpty(pp.dftVal())) {
              pp.setVal(subForm, pp.dftVal());
              continue;
            }
            throw new IllegalStateException("have not prepare for table:" + tab2cols.getKey()
              + " creating,prop name:" + pentry.getKey() + ",subform class:" + subForm.getClass().getName());
          }
        }
        return subForm;

      }
    });
  }

  /**
   * Do get datasource table. ????????????table???git????????????
   *
   * @param context the context
   */
  public void doGetDatasourceTableById(Context context) throws IOException {
    Integer dbId = this.getInt("id");
    String tableName = this.getString("labelName");
    com.qlangtech.tis.workflow.pojo.DatasourceDb db
      = this.getWorkflowDAOFacade().getDatasourceDbDAO().selectByPrimaryKey(dbId);
    Objects.requireNonNull(db, "db can not be null");
    //TISTable tableConfig = this.offlineManager.getTableConfig(this, tableId);
    //this.setBizResult(context, tableConfig);

    DataxReader dbDataxReader = OfflineManager.getDBDataxReader(this, db.getName());
    this.setBizResult(context, new DescriptorsJSON(dbDataxReader.getDescriptor()).getDescriptorsJSON());
  }

  /**
   * ??????sql????????????sql???????????????
   */
  @Func(value = PermissionConstant.PERMISSION_DATASOURCE_EDIT, sideEffect = false)
  public void doReflectTableCols(Context context) throws Exception {
    String topology = this.getString("topology");
    if (StringUtils.isEmpty(topology)) {
      throw new IllegalArgumentException("param topology can not be null");
    }
    SqlDataFlowTopology wfTopology = SqlTaskNodeMeta.getSqlDataFlowTopology(topology);
    Map<String, DependencyNode> /**
     * id
     */
      dumpNodes = wfTopology.getDumpNodes().stream().collect(Collectors.toMap((d) -> d.getId(), (d) -> d));
    com.alibaba.fastjson.JSONArray sqlAry = this.parseJsonArrayPost();
    com.alibaba.fastjson.JSONObject j = null;
    // String sql = null;
    // List<RowMetaData> rowMetaData = null;
    List<SqlCols> colsMeta = Lists.newArrayList();
    SqlCols sqlCols = null;
    DependencyNode dumpNode = null;
    TISTable tabCfg = null;
    // List<ColumnMetaData> reflectCols = null;
    for (int i = 0; i < sqlAry.size(); i++) {
      j = sqlAry.getJSONObject(i);
      // sql = j.getString("sql");
      sqlCols = new SqlCols();
      sqlCols.setKey(j.getString("key"));
      String dumpNodeId = sqlCols.getKey();
      dumpNode = dumpNodes.get(dumpNodeId);
      if (dumpNode == null) {
        throw new IllegalStateException("key:" + dumpNodeId + " can not find relevant dump node in topplogy '" + topology + "'");
      }

      // tabCfg = GitUtils.$().getTableConfig(dumpNode.getDbName(), dumpNode.getName());
      DataSourceFactoryPluginStore dbPlugin = TIS.getDataBasePluginStore(new PostedDSProp(dumpNode.getDbName()));
      TISTable tisTable = dbPlugin.loadTableMeta(dumpNode.getName());
      if (CollectionUtils.isEmpty(tisTable.getReflectCols())) {
        throw new IllegalStateException("db:" + dumpNode.getDbName() + ",table:" + dumpNode.getName() + " relevant table col reflect cols can not be empty");
      }
      sqlCols.setCols(tisTable.getReflectCols());
      colsMeta.add(sqlCols);
    }
    this.setBizResult(context, colsMeta);
  }

  public static class SqlCols {

    private String key;

    List<ColumnMetaData> cols;

    public String getKey() {
      return this.key;
    }

    public void setKey(String key) {
      this.key = key;
    }

    public List<ColumnMetaData> getCols() {
      return this.cols;
    }

    public void setCols(List<ColumnMetaData> cols) {
      this.cols = cols;
    }
  }

  public static final String COMPONENT_START = "component.start";

  public static final String COMPONENT_END = "component.end";

  /**
   * Do execute workflow. ?????????????????????<br>
   * ???????????????DataFlow???????????????
   *
   * @param context the context
   * @throws Exception the exception
   */
  @Func(value = PermissionConstant.DATAFLOW_MANAGE)
  public void doExecuteWorkflow(Context context) throws Exception {
    Integer id = this.getInt("id");
    List<PostParam> params = Lists.newArrayList();
    WorkFlow df = this.getWorkflowDAOFacade().getWorkFlowDAO().selectByPrimaryKey(id);
    Assert.assertNotNull(df);
    params.add(new PostParam(IFullBuildContext.KEY_WORKFLOW_NAME, df.getName()));
    params.add(new PostParam(IFullBuildContext.KEY_WORKFLOW_ID, String.valueOf(id)));
    // TODO ???????????????DF??????????????????????????????????????????????????????????????????????????????build
    params.add(new PostParam(IFullBuildContext.KEY_APP_SHARD_COUNT, IFullBuildContext.KEY_APP_SHARD_COUNT_SINGLE));
    params.add(new PostParam(COMPONENT_START, FullbuildPhase.FullDump.getName()));
    params.add(new PostParam(COMPONENT_END, FullbuildPhase.JOIN.getName()));
    if (!CoreAction.triggerBuild(this, context, params).success) {
      // throw new IllegalStateException("dataflowid:" + id + " trigger faild");
    }
  }

  public static final String KEY_DATA_READER_SETTED = "dataReaderSetted";

  @Func(value = PermissionConstant.PERMISSION_DATASOURCE_EDIT, sideEffect = false)
  public void doGetDsRelevantReaderDesc(Context context) {
    com.alibaba.fastjson.JSONObject form = this.parseJsonPost();
    Integer dbId = form.getInteger("dbId");
    if (dbId == null) {
      throw new IllegalStateException("dbId can not be null");
    }
    com.qlangtech.tis.workflow.pojo.DatasourceDb db = this.offlineDAOFacade.getDatasourceDbDAO().selectByPrimaryKey(dbId);
    //IPluginStore<DataSourceFactory> dbPlugin = TIS.getDataBasePluginStore(new PostedDSProp(db.getName(), DbScope.DETAILED));
    //DataSourceFactory.BaseDataSourceFactoryDescriptor descriptor = (DataSourceFactory.BaseDataSourceFactoryDescriptor) dbPlugin.getPlugin().getDescriptor();
    OfflineManager.DBDataXReaderDescName defaultDataXReaderDescName = offlineManager.getDBDataXReaderDescName(db.getName());
    Map<String, Object> result = Maps.newHashMap();
    if (!defaultDataXReaderDescName.readerDescName.isPresent()) {
      //throw new IllegalStateException("datasource:" + db.getName() + " desc:" + descriptor.getDisplayName() + " has not relevant DataXReader defined");
      // result.put(KEY_DATA_READER_SETTED + "NotSupport", descriptor.getId());
      this.addErrorMessage(context, "??????:" + defaultDataXReaderDescName.dsDescriptor.getId() + " ??????????????????");
      return;
    }

    DataxReader dataxReader = OfflineManager.getDBDataxReader(this, db.getName());
    if (dataxReader != null) {
      result.put(KEY_DATA_READER_SETTED, true);
      //this.setBizResult(context, result);
      //return;
    }

    DescriptorExtensionList<DataxReader, Descriptor<DataxReader>> descriptorList
      = TIS.get().getDescriptorList(DataxReader.class);
    Optional<DataxReader.BaseDataxReaderDescriptor> dataXReaderDesc = descriptorList.stream().filter((de) -> {
      return defaultDataXReaderDescName.getReaderDescName().equals(de.getDisplayName());
    }).map((d) -> (DataxReader.BaseDataxReaderDescriptor) d).findFirst();

    if (!dataXReaderDesc.isPresent()) {
      throw new IllegalStateException("DataXReaderDescName:"
        + defaultDataXReaderDescName.getReaderDescName() + " can not find relevant DataXReader Descriptor");
    }


    result.put("readerDesc", new DescriptorsJSON(dataXReaderDesc.get()).getDescriptorsJSON());
    result.put("processMeta", DataxAction.getDataXBasicProcessMetaByReader(dataXReaderDesc.get()));

    this.setBizResult(context, result);
  }

  /**
   * Do check table logic name repeat. ??????????????? ???????????????????????????<br/>
   * ??????????????????????????????,??????SQL??????
   */
  @Func(value = PermissionConstant.PERMISSION_DATASOURCE_EDIT, sideEffect = false)
  public void doCheckTableLogicNameRepeat(Context context) {
    com.alibaba.fastjson.JSONObject form = this.parseJsonPost();
    this.errorsPageShow(context);
    boolean updateMode = !form.getBoolean("isAdd");
    // ?????????
    // TODO ???????????? table???????????????
    String table = form.getString("tableName");
    if (StringUtils.isEmpty(table)) {
      this.addErrorMessage(context, "??????????????????");
      return;
    }

    final String tableLogicName = table;
    if (StringUtils.equals("db_config", tableLogicName)) {
      this.addErrorMessage(context, "???????????????'db_config'");
      return;
    }
    Integer dbId = form.getIntValue("dbId");
    com.qlangtech.tis.workflow.pojo.DatasourceDb db = this.offlineDAOFacade.getDatasourceDbDAO().selectByPrimaryKey(dbId);
    if (!updateMode && offlineManager.checkTableLogicNameRepeat(tableLogicName, db)) {
      this.addErrorMessage(context, "?????????????????????????????????");
      return;
    }

    IPluginStore<DataSourceFactory> dbPlugin = TIS.getDataBasePluginStore(new PostedDSProp(db.getName(), DbScope.DETAILED));

    List<ColumnMetaData> cols = dbPlugin.getPlugin().getTableMetadata(table);// offlineManager.getTableMetadata(db.getName(), table);
    if (cols.size() < 1) {
      this.addErrorMessage(context, "???:[" + table + "]???????????????");
      return;
    }

    StringBuffer extractSQL = ColumnMetaData.buildExtractSQL(table, cols);

    TableReflect tableReflect = new TableReflect();
    tableReflect.setCols(cols);
    tableReflect.setSql(extractSQL.toString());
    this.setBizResult(context, tableReflect);
  }

  /**
   * ??????db???????????????
   *
   * @param context
   */
  public void doSyncDb(Context context) {
    Integer dbId = this.getInt("id");
    if (dbId == null) {
      this.addErrorMessage(context, "dbId????????????");
      return;
    }
    String dbName = this.getString("dbName");
    // this.offlineManager.syncDb(dbId, dbName, this, context);
  }

  /**
   * ??????table???????????????
   *
   * @param context
   */
  public void doSyncTable(Context context) {
    Integer tableId = this.getInt("id");
    if (tableId == null) {
      this.addErrorMessage(context, "table id ????????????");
      return;
    }
    String tableLogicName = this.getString("tableLogicName");
    // this.offlineManager.syncTable(tableId, tableLogicName, this,
    // context);
  }

  /**
   * ????????????????????????????????????db??????
   *
   * @param context
   */
  public void doSyncDbRecord(Context context) {
    Integer id = this.getInt("id");
    if (id == null) {
      this.addErrorMessage(context, "id????????????");
      this.setBizResult(context, false);
      return;
    }
    String name = this.getString("name");
    com.qlangtech.tis.workflow.pojo.DatasourceDb datasourceDb = new com.qlangtech.tis.workflow.pojo.DatasourceDb();
    datasourceDb.setId(id);
    datasourceDb.setName(name);
    datasourceDb.setSyncOnline(new Byte("0"));
    Date now = new Date();
    datasourceDb.setCreateTime(now);
    this.offlineManager.syncDbRecord(datasourceDb, this, context);
  }

  /**
   * ????????????????????????????????????table??????
   *
   * @param context
   */
  public void doSyncTableRecord(Context context) {
    Integer id = this.getInt("id");
    if (id == null) {
      this.addErrorMessage(context, "id????????????");
      this.setBizResult(context, false);
      return;
    }
    String name = this.getString("name");
    String tableLogicName = this.getString("tableLogicName");
    Integer dbId = this.getInt("db_id");
    if (dbId == null) {
      this.addErrorMessage(context, "db_id????????????");
      this.setBizResult(context, false);
      return;
    }
    DatasourceTable datasourceTable = new DatasourceTable();
    datasourceTable.setId(id);
    datasourceTable.setName(name);
    datasourceTable.setDbId(dbId);
    Date now = new Date();
    datasourceTable.setCreateTime(now);
    this.offlineManager.syncTableRecord(datasourceTable, this, context);
  }

  /**
   * ??????db
   *
   * @param context
   */
  @Func(value = PermissionConstant.PERMISSION_DATASOURCE_EDIT)
  public void doDeleteDatasourceDbById(Context context) throws Exception {
    Integer id = this.getInt("id");
    Objects.requireNonNull(id, "param id can not be null");
    DbScope dbModel = DbScope.parse(this.getString("dbModel"));
    this.offlineManager.deleteDbById(id, dbModel, this, context);
  }

  /**
   * ??????table
   *
   * @param context
   */
  @Func(value = PermissionConstant.PERMISSION_DATASOURCE_EDIT)
  public void doDeleteDatasourceTableById(Context context) {
    Integer id = this.getInt("id");
    this.offlineManager.deleteDatasourceTableById(id, this, context);
  }

  /**
   * ????????????
   *
   * @param context
   */
  public void doDeleteWorkflowChange(Context context) throws Exception {
    Integer id = this.getInt("id");
    if (id == null) {
      this.addErrorMessage(context, "workflow id????????????");
      return;
    }
    this.offlineManager.deleteWorkflowChange(id, this, context);
    // this.doGetWorkflowChanges(context);
  }

  /**
   * ???????????? ???????????????
   *
   * @param context
   */
  public void doConfirmWorkflowChange(Context context) throws Exception {
    Integer id = this.getInt("id");
    if (id == null) {
      this.addErrorMessage(context, "workflow id????????????");
      return;
    }
    this.offlineManager.confirmWorkflowChange(id, this, context);
  }

  /**
   * ??????????????????????????????
   *
   * @param context
   */
  public void doGetWorkflowConfig(Context context) {
    Integer id = this.getInt("id");
    if (id == null) {
      this.addErrorMessage(context, "??????????????????id");
      return;
    }
    this.setBizResult(context, this.offlineManager.getWorkflowConfig(id, true));
  }

  // /**
  // * ??????????????????????????????
  // *
  // * @param context
  // */
  // public void doGetWorkflowConfigBranch(Context context) {
  // Integer id = this.getInt("id");
  // if (id == null) {
  // this.addErrorMessage(context, "??????????????????id");
  // return;
  // }
  // this.setBizResult(context, this.offlineManager.getWorkflowConfig(id, false));
  // }
  // /**
  // * ????????????
  // *
  // * @param context
  // */
  // public void doGetWorkflowConfigSha1(Context context) {
  // String name = this.getString("name");
  // if (StringUtils.isBlank(name)) {
  // this.addErrorMessage(context, "???????????????????????????");
  // return;
  // }
  // String gitSha1 = this.getString("gitSha1");
  // if (StringUtils.isBlank(gitSha1)) {
  // this.addErrorMessage(context, "??????????????????commit id");
  // return;
  // }
  // this.setBizResult(context, this.offlineManager.getWorkflowConfig(name,
  // gitSha1));
  // }
  // public void doUseWorkflowChange(Context context) {
  // Integer id = this.getInt("id");
  // if (id == null) {
  // this.addErrorMessage(context, "???????????????id");
  // return;
  // }
  // this.offlineManager.useWorkflowChange(id, this, context);
  // }
  // public void doCompareWorkflowChanges(Context context) {
  // String path = this.getString("path");
  // String fromVersion = this.getString("fromVersion");
  // String toVersion = this.getString("toVersion");
  // String fromString =
  // GitUtils.$().getWorkflowSha(GitUtils.WORKFLOW_GIT_PROJECT_ID, fromVersion,
  // path).getTask();
  // String toString =
  // GitUtils.$().getWorkflowSha(GitUtils.WORKFLOW_GIT_PROJECT_ID, toVersion,
  // path).getTask();
  // this.setBizResult(context, getTwoStringDiffHtml(fromString, toString));
  // }
  // private static String getTwoStringDiffHtml(String s1, String s2) {
  // StringBuilder sb = new StringBuilder();
  // LinkedList<diff_match_patch.Diff> differ = DIFF_MATCH_PATCH.diff_main(s1, s2,
  // true);
  //
  // for (diff_match_patch.Diff d : differ) {
  //
  // if (d.operation == diff_match_patch.Operation.EQUAL) {
  // sb.append(StringEscapeUtils.escapeXml(d.text));
  // } else if (d.operation == diff_match_patch.Operation.DELETE) {
  // sb.append("<span
  // style='text-decoration:line-through;background-color:pink;'>")
  // .append(StringEscapeUtils.escapeXml(d.text)).append("</span>");
  // } else if (d.operation == diff_match_patch.Operation.INSERT) {
  // sb.append("<span
  // style=\"background-color:#00FF00;\">").append(StringEscapeUtils.escapeXml(d.text))
  // .append("</span>");
  // }
  //
  // }
  // return sb.toString();
  // }
  @Autowired
  public void setWfDaoFacade(IWorkflowDAOFacade facade) {
    this.offlineDAOFacade = facade;
  }

  public static class DatasourceDb {

    int id;

    String name;

    List<DatasourceTable> tables;

    // byte syncOnline;

    public DatasourceDb() {
    }

    public int getId() {
      return id;
    }

    public void setId(int id) {
      this.id = id;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public List<DatasourceTable> getTables() {
      return tables;
    }

    public void setTables(List<DatasourceTable> tables) {
      this.tables = tables;
    }

    public void addTable(DatasourceTable datasourceTable) {
      if (this.tables == null) {
        this.tables = new LinkedList<>();
      }
      this.tables.add(datasourceTable);
    }

//    public byte getSyncOnline() {
//      return syncOnline;
//    }
//
//    public void setSyncOnline(byte syncOnline) {
//      this.syncOnline = syncOnline;
//    }
  }

  public static String getHiveType(int type) {
    switch (type) {
      case BIT:
      case TINYINT:
      case SMALLINT:
      case INTEGER:
        return "INT";
      case BIGINT:
        return "BIGINT";
      case FLOAT:
      case REAL:
      case DOUBLE:
      case NUMERIC:
      case DECIMAL:
        return "DOUBLE";
      default:
        return "STRING";
    }
  }

  public static String getDbType(int type) {
    switch (type) {
      case BIT:
        return "BIT";
      case TINYINT:
        return "TINYINT";
      case SMALLINT:
        return "SMALLINT";
      case INTEGER:
        return "INTEGER";
      case BIGINT:
        return "BIGINT";
      case FLOAT:
        return "FLOAT";
      case REAL:
        return "REAL";
      case DOUBLE:
        return "DOUBLE";
      case NUMERIC:
        return "NUMERIC";
      case DECIMAL:
        return "DECIMAL";
      default:
        return "VARCHAR";
    }
  }
}
