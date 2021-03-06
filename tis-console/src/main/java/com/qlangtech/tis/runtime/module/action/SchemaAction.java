/**
 *   Licensed to the Apache Software Foundation (ASF) under one
 *   or more contributor license agreements.  See the NOTICE file
 *   distributed with this work for additional information
 *   regarding copyright ownership.  The ASF licenses this file
 *   to you under the Apache License, Version 2.0 (the
 *   "License"); you may not use this file except in compliance
 *   with the License.  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.qlangtech.tis.runtime.module.action;

import com.alibaba.citrus.turbine.Context;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Maps;
import com.qlangtech.tis.TIS;
import com.qlangtech.tis.common.utils.Assert;
import com.qlangtech.tis.datax.IDataxProcessor;
import com.qlangtech.tis.datax.ISearchEngineTypeTransfer;
import com.qlangtech.tis.plugin.IPluginStore;
import com.qlangtech.tis.plugin.ds.ISelectedTab;
import com.qlangtech.tis.datax.impl.DataxProcessor;
import com.qlangtech.tis.datax.impl.DataxReader;
import com.qlangtech.tis.fullbuild.indexbuild.LuceneVersion;
import com.qlangtech.tis.manage.ISolrAppSource;
import com.qlangtech.tis.manage.PermissionConstant;
import com.qlangtech.tis.manage.Savefilecontent;
import com.qlangtech.tis.manage.biz.dal.dao.IServerGroupDAO;
import com.qlangtech.tis.manage.biz.dal.pojo.*;
import com.qlangtech.tis.manage.common.*;
import com.qlangtech.tis.manage.spring.aop.Func;
import com.qlangtech.tis.offline.DataxUtils;
import com.qlangtech.tis.plugin.PluginStore;
import com.qlangtech.tis.plugin.annotation.Validator;
import com.qlangtech.tis.plugin.ds.ColumnMetaData;
import com.qlangtech.tis.plugin.ds.ReflectSchemaFieldType;
import com.qlangtech.tis.plugin.solr.schema.FieldTypeFactory;
import com.qlangtech.tis.pubhook.common.RunEnvironment;
import com.qlangtech.tis.runtime.module.action.jarcontent.SaveFileContentAction;
import com.qlangtech.tis.runtime.module.misc.*;
import com.qlangtech.tis.solrdao.*;
import com.qlangtech.tis.solrdao.SolrFieldsParser.SolrType;
import com.qlangtech.tis.solrdao.impl.ParseResult;
import com.qlangtech.tis.solrdao.pojo.PSchemaField;
import com.qlangtech.tis.trigger.util.JsonUtil;
import com.qlangtech.tis.workflow.pojo.WorkFlow;
import com.qlangtech.tis.workflow.pojo.WorkFlowCriteria;
import com.yushu.tis.xmodifier.XModifier;
import org.apache.commons.beanutils.BeanUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.jdom2.DocType;
import org.jdom2.Document;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

//import org.json.JSONArray;
//import org.json.JSONException;
//import org.json.JSONObject;

/**
 * @author ?????????baisui@qlangtech.com???
 * @date 2016???11???11???
 */
public class SchemaAction extends BasicModule {

  public static final String FIELD_PROJECT_NAME = "projectName";
  protected static final String FIELD_WORKFLOW = "workflow";
  protected static final String FIELD_DS_TYPE = "dsType";
  protected static final String FIELD_Recept = "recept";
  protected static final String FIELD_DptId = "dptId";
  private static final long serialVersionUID = 1L;

  protected static final String INDEX_PREFIX = "search4";

  //private static Logger log = LoggerFactory.getLogger(SchemaAction.class);

  /**
   * DataX ?????????????????????es???????????????
   *
   * @param context
   * @throws Exception
   */
  public void doGetEsTplFields(Context context) throws Exception {
    String dataxName = this.getString(DataxUtils.DATAX_NAME);

    StepType stepType = StepType.parse(this.getString("stepType"));
    //DataxProcessor process = DataxProcessor.load(this, dataxName);
    //  IDataxProcessor.TableMap tabMapper = null;
    //  ESField field = null;

    ISearchEngineTypeTransfer typeTransfer = ISearchEngineTypeTransfer.load(this, dataxName);
    DataxReader dataxReader = DataxReader.load(this, dataxName);
    // ESField field = null;
    if (stepType.update) {
      DataxProcessor dataxProcessor = DataxProcessor.load(this, dataxName);

      for (Map.Entry<String, IDataxProcessor.TableAlias> e : dataxProcessor.getTabAlias().entrySet()) {
        writerStructFields(context, e.getValue(), typeTransfer);
        return;
      }
    } else {
      for (ISelectedTab tab : dataxReader.getSelectedTabs()) {
        // ESSchema parseResult = new ESSchema();
        SchemaMetaContent tplSchema = typeTransfer.initSchemaMetaContent(tab);
        this.setBizResult(context, tplSchema.toJSON());
        return;
      }
    }

    throw new IllegalStateException("have not find any tab in DataXReader");
  }

  /**
   * ?????????????????????????????????workflow??????Schema ????????????
   *
   * @param context
   * @throws Exception
   */
  public void doGetTplFields(Context context) throws Exception {
    AddAppAction.ExtendApp app = this.parseJsonPost(AddAppAction.ExtendApp.class);
    CreateAppResult validateResult = this.createNewApp(context, app, -1, /* publishSnapshotId */
      null, /* schemaContent */
      true);
    if (!validateResult.isSuccess()) {
      return;
    }

    ISolrAppSource appSource = app.createAppSource(this);
    SchemaResult tplSchema = mergeWfColsWithTplCollection(this, context, appSource, ISchemaPluginContext.NULL);
    Objects.requireNonNull(tplSchema, "tplSchema can not be null");
    // ???????????????
    this.setBizResult(context, tplSchema.toJSON());
  }

  // ???????????????
  public static SolrFieldsParser.ParseResultCallback parseResultCallback4test = (cols, result) -> {
  };

  /**
   * ????????????workflow????????????????????????????????????Schema??????
   *
   * @param module
   * @param context
   * @param appSource
   * @return
   * @throws Exception
   */
  public static SchemaResult mergeWfColsWithTplCollection(BasicModule module, Context context
    , ISolrAppSource appSource, final ISchemaPluginContext schemaPlugin, SolrFieldsParser.ParseResultCallback... parseResultCallback) throws Exception {
    // ??????version???????????????
    Application tplApp = getTemplateApp(module);
    SchemaResult tplSchema = getTemplateSchema(module, context, tplApp);
    if (!tplSchema.isSuccess()) {
      return null;
    }
    ParseResult parseResult = (ParseResult) tplSchema.getParseResult();
    SolrType strType = parseResult.getTisType(ReflectSchemaFieldType.STRING.literia);
    List<ColumnMetaData> cols = appSource.reflectCols();
    for (ColumnMetaData colName : cols) {
      PSchemaField f = new PSchemaField();
      f.setName(colName.getKey());
      f.setType(strType);
      f.setStored(true);
      f.setIndexed(false);
      f.setMltiValued(false);
      f.setDocValue(false);
      parseResult.dFields.add(f);
    }

    parseResult.setUniqueKey(null);
    for (SolrFieldsParser.ParseResultCallback c : parseResultCallback) {
      c.process(cols, parseResult);
    }

    parseResult.addReservedFields();
    tplSchema.content = XModifier.modifySchemaContent(tplSchema.content, (document2, modifier) -> {
      modifier.addModify("/fields/field(:delete)");
      modifier.addModify("/sharedKey(:delete)");
      modifier.deleteUniqueKey();
      updateSchemaXML(parseResult.types, schemaPlugin, parseResult, document2, modifier);
    });

    parseResultCallback4test.process(cols, parseResult);
    return tplSchema;
  }


  private static SchemaResult getTemplateSchema(BasicModule module, Context context, Application tplApp) throws Exception {
    UploadResource schemaRes = getAppSchema(module, tplApp);
    // ???????????????fields????????????
    SchemaResult schemaResult = SchemaResult.parseSchemaResult(module, context, schemaRes.getContent(), false, (fieldType) -> false);
    if (!schemaResult.isSuccess()) {
      return schemaResult;
    }
    schemaResult.setTplAppId(tplApp.getAppId());
    ISchema parseResult = schemaResult.getParseResult();
    if (parseResult == null) {
      throw new IllegalStateException("schemaResult:'" + tplApp.getProjectName() + "'.parseResult can not be null");
    }
    parseResult.clearFields();
    return schemaResult;
  }

  private WorkFlow getWorkflow(String wfName) {
    WorkFlowCriteria wquery = new WorkFlowCriteria();
    wquery.createCriteria().andNameEqualTo(wfName);
    List<WorkFlow> workFlows = this.getWorkflowDAOFacade().getWorkFlowDAO().selectByExample(wquery, 1, 1);
    Optional<WorkFlow> first = workFlows.stream().findFirst();
    if (!first.isPresent()) {
      throw new IllegalStateException("workflow:" + wfName + " can not find relevant instance in db");
    }
    return first.get();
  }

  public void doGetXmlContent(Context context) throws Exception {
    Application app = new Application();
    app.setAppId(this.getAppDomain().getAppid());
    UploadResource schemaResource = getAppSchema(this, app);
    this.doGetXmlContent(context, schemaResource, Collections.emptyList());
  }

  private void doGetXmlContent(Context context, UploadResource schemaResource, List<SchemaField> fields) throws Exception {
    JSONObject schema = new JSONObject();
    schema.put("schema", new String(schemaResource.getContent(), getEncode()));
    JSONArray disabledInputs = new JSONArray();
//    for (SchemaField f : fields) {
//      // if (f.isInputDisabled()) {
//      // disabledInputs.put(f.getName());
//      // }
//    }
    schema.put("inputDisabled", disabledInputs);
    this.setBizResult(context, schema);
  }

  private enum StepType {
    CreateDatax("createDatax", false), UpdateDataxWriter("UpdateDataxWriter", true), UpdateDataxReader("UpdateDataxReader", true);
    private String literia;
    private final boolean update;

    StepType(String literia, boolean update) {
      this.literia = literia;
      this.update = update;
    }

    static StepType parse(String val) {

      for (StepType t : StepType.values()) {
        if (t.literia.equals(val)) {
          return t;
        }
      }

      throw new IllegalStateException("val is illegal:" + val);
    }


  }

  /**
   * ES????????????????????????????????????(??????????????????)
   */
  @Func(value = PermissionConstant.APP_SCHEMA_UPDATE, sideEffect = false)
  public void doToggleEsExpertModel(Context context) throws Exception {
    UploadSchemaWithRawContentForm form = this.getFormValues();
    if (!validateStupidContent(context, form)) {
      return;
    }
    ISearchEngineTypeTransfer typeTransfer = ISearchEngineTypeTransfer.load(this, form.getDataxName());

    JSONObject mergeTarget = ISearchEngineTypeTransfer.getOriginExpertSchema(form.getSchemaXmlContent());

    String esMapping = JsonUtil.toString(typeTransfer.mergeFromStupidModel(form, mergeTarget));
    UploadResource schemaResource = new UploadResource();
    schemaResource.setContent(esMapping.getBytes(TisUTF8.get()));
    this.doGetXmlContent(context, schemaResource, form.getFields());
  }

  /**
   * ????????????????????????????????????(??????????????????)
   */
  @Func(value = PermissionConstant.APP_SCHEMA_UPDATE, sideEffect = false)
  public void doToggleSolrExpertModel(Context context) throws Exception {
    UploadSchemaWithRawContentForm form = this.getFormValues();
    if (!validateStupidContent(context, form)) {
      return;
    }
    String schema = this.createSchema(ISchemaPluginContext.NULL, form, context);

    UploadResource schemaResource = new UploadResource();
    schemaResource.setContent(schema.getBytes(Charset.forName(getEncode())));
    this.doGetXmlContent(context, schemaResource, form.getFields());
  }

  /**
   * ????????????????????????
   *
   * @param context
   * @throws Exception
   */
  @Func(value = PermissionConstant.APP_SCHEMA_UPDATE, sideEffect = true)
  public void doSaveByExpertModel(Context context) throws Exception {
    VisualizingSchemaForm form = this.parseJsonPost(VisualizingSchemaForm.class);
    if (!validateStupidContent(context, form.getVisualizingForm())) {
      return;
    }

    Savefilecontent meta = form.getMeta();

    String schema = this.createSchema(SchemaAction.createSchemaPlugin(this.getCollectionName()), form.getVisualizingForm(), context);
    SchemaAction.CreateSnapshotResult createResult //
      = createNewSnapshot(context, createSchemaPlugin(this.getCollectionName())
      , this.getSnapshotViewDAO().getView(meta.getSnapshotid()), ConfigFileReader.FILE_SCHEMA, schema.getBytes(TisUTF8.get())
      , this, this, meta.getMemo(), Long.parseLong(this.getUser().getId()), this.getLoginUserName());

    if (!createResult.isSuccess()) {
      return;
    }
    this.setBizResult(context, createResult);
    this.addActionMessage(context, "??????????????????,??????snapshot:" + createResult.getNewId());
  }

  private static final Pattern PATTERN_FIELD = Pattern.compile("[a-z|_][\\w|_]+");

  /**
   * ???????????????????????????
   *
   * @param context
   * @param form
   * @return
   */
  private boolean validateStupidContent(Context context, UploadSchemaWithRawContentForm form) {
    FieldErrors fieldsErrors = new FieldErrors();
    boolean hasBlankError = false;
    boolean hasFieldTypeError = false;
    // boolean hasNamePatternError = false;
    // ???????????????
    boolean hasDuplicateError = false;
    Set<String> duplicate = new HashSet<String>();
    for (SchemaField f : form.getFields()) {
      if (StringUtils.isBlank(f.getName())) {
        if (!hasBlankError) {
          this.addErrorMessage(context, "????????????????????????");
          hasBlankError = true;
        }
        FieldErrorInfo err = fieldsErrors.getFieldErrorInfo(f.getId());// new FieldErrorInfo(f.getId());
        err.setFieldNameError(true);
      } else {
        if (!duplicate.add(f.getName())) {
          // if (!hasDuplicateError) {
          this.addErrorMessage(context, "????????????" + f.getName() + "???????????????");
          // }
          // ?????????
          hasDuplicateError = true;
          FieldErrorInfo err = fieldsErrors.getFieldErrorInfo(f.getId());
          err.setFieldNameError(true);
          // fieldsErrors.add(err);
        } else {
          if (!PATTERN_FIELD.matcher(f.getName()).matches() && !f.isDynamic()) {
            // if (!hasNamePatternError) {
            this.addErrorMessage(context, "????????????" + f.getName() + "??????????????????[a-z]??????????????????????????????????????????????????????????????????????????????????????????");
            // }
            //    hasNamePatternError = true;
            FieldErrorInfo err = fieldsErrors.getFieldErrorInfo(f.getId());
            err.setFieldNameError(true);

          } else if (StringUtils.isBlank(f.getFieldtype())) {
            this.addErrorMessage(context, "???????????????" + f.getName() + "???????????????");
            hasFieldTypeError = true;
            FieldErrorInfo err = fieldsErrors.getFieldErrorInfo(f.getId());
            err.setFieldTypeError(true);
            //fieldsErrors.add(err);
          } else if ("string".equalsIgnoreCase(f.getFieldtype())  //
            && (StringUtils.isBlank(f.getTokenizerType())
            || "-1".equalsIgnoreCase(f.getTokenizerType()))) {
            // if (!hasFieldTypeError) {
            this.addErrorMessage(context, "???????????????" + f.getName() + "?????????????????????");
            hasFieldTypeError = true;
            // }
            FieldErrorInfo err = fieldsErrors.getFieldErrorInfo(f.getId());
            err.setFieldTypeError(true);
            // fieldsErrors.add(err);
          }
        }

        if (!f.getSortable() && !f.isIndexed() && !f.isStored()) {
          this.addErrorMessage(context, SolrFieldsParser.getFieldPropRequiredErr(f.getName()));
          FieldErrorInfo err = fieldsErrors.getFieldErrorInfo(f.getId());
          err.setFieldPropRequiredError(true);
          // fieldsErrors.add(err);
        }

      }
    }
    if (fieldsErrors.hasErrors() || hasBlankError || hasFieldTypeError || hasDuplicateError || hasDuplicateError) {
      setBizResult(context, fieldsErrors.getAllErrs());
      return false;
    }
    return true;
  }

  /**
   * Solr????????????????????????????????????(??????????????????)
   */
  @Func(value = PermissionConstant.APP_SCHEMA_UPDATE, sideEffect = false)
  public void doToggleSolrStupidModel(Context context) throws Exception {
    // ??????xml??????
    com.alibaba.fastjson.JSONObject body = this.parseJsonPost();
    byte[] schemaContent = body.getString("content").getBytes(TisUTF8.get());
    this.getStructSchema(context, ISchemaPluginContext.NULL, schemaContent);
  }

  /**
   * ES:????????????????????????????????????(??????????????????)
   */
  @Func(value = PermissionConstant.APP_SCHEMA_UPDATE, sideEffect = false)
  public void doToggleEsStupidModel(Context context) throws Exception {
    // ??????xml??????
    com.alibaba.fastjson.JSONObject body = this.parseJsonPost();

    ISearchEngineTypeTransfer typeTransfer = ISearchEngineTypeTransfer.load(this, body.getString(DataxUtils.DATAX_NAME));

    //  final String content = body.getString("content");
    //byte[] schemaContent = body.getString("content").getBytes(TisUTF8.get());
    //JSONArray fields = JSON.parseArray(content);
    writerStructFields(context, body, typeTransfer);
  }

  private void writerStructFields(Context context, IDataxProcessor.TableAlias tableAlias, ISearchEngineTypeTransfer typeTransfer) {

    SchemaMetaContent schemaContent = new SchemaMetaContent();
    //schemaContent.content = content.getBytes(TisUTF8.get());
    schemaContent.parseResult = typeTransfer.projectionFromExpertModel(tableAlias, (content) -> {
      schemaContent.content = content;
    });
    // this.getStructSchema(context, ISchemaPluginContext.NULL, schemaContent);

    this.setBizResult(context, schemaContent.toJSON());
  }

  private void writerStructFields(Context context, JSONObject body, ISearchEngineTypeTransfer typeTransfer) {
    final String content = body.getString("content");
    SchemaMetaContent schemaContent = new SchemaMetaContent();
    schemaContent.content = content.getBytes(TisUTF8.get());
    schemaContent.parseResult = typeTransfer.projectionFromExpertModel(body);
    // this.getStructSchema(context, ISchemaPluginContext.NULL, schemaContent);

    this.setBizResult(context, schemaContent.toJSON());
  }

  /**
   * @param context
   * @throws Exception
   */
  @Func(value = PermissionConstant.APP_SCHEMA_UPDATE, sideEffect = false)
  public void doGetStructSchema(Context context) throws Exception {
    String appName = this.getString("app");
    if (StringUtils.isEmpty(appName)) {
      throw new IllegalArgumentException("param 'app' can not be null");
    }
    Application tplApp = this.getApplicationDAO().selectByName(appName);
    if (tplApp == null) {
      throw new IllegalStateException("index:" + appName + ", relevant app can not be null");
    }
    this.getStructSchema(context, tplApp);
  }

  private boolean getStructSchema(Context context, Application app, ISchemaJsonVisitor... schemaVisitor) throws Exception {
    UploadResource schemaRes = getAppSchema(this, app);
    return this.getStructSchema(context, SchemaAction.createSchemaPlugin(app.getProjectName()), schemaRes.getContent(), schemaVisitor);
  }

  /**
   * ?????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
   *
   * @param context
   */
  public void doGetApp(Context context) throws Exception {
    final String collectionName = this.getString("name");
    if (StringUtils.isEmpty(collectionName)) {
      throw new IllegalArgumentException("param name can not be null");
    }
    AddAppAction.ExtendApp app = new AddAppAction.ExtendApp();
    Application a = this.getApplicationDAO().selectByName(collectionName);
    BeanUtils.copyProperties(app, a);
    WorkFlow df = this.getWorkflowDAOFacade().getWorkFlowDAO().selectByPrimaryKey(a.getWorkFlowId());
    if (df == null) {
      this.addErrorMessage(context, "????????????'" + collectionName + "'???????????????????????????");
      return;
    }
    app.setWorkflow(df.getId() + ":" + df.getName());
    DepartmentCriteria dc = new DepartmentCriteria();
    dc.createCriteria();
    List<Department> dpts = this.getDepartmentDAO().selectByExample(dc);
    if (dpts.size() < 1) {
      this.addErrorMessage(context, "??????????????????????????????");
      return;
    }
    app.setSelectableDepartment(dpts.stream().map((r) -> new Option(r.getName(), String.valueOf(r.getDptId()))).collect(Collectors.toList()));
    Map<String, Object> result = Maps.newHashMap();
    result.put("app", app);
    if (!getStructSchema(context, app, (schema) -> {
      result.put("schema", schema);
    })) {
      return;
    }
    this.setBizResult(context, result);
  }

  /**
   * ??????????????????schema??????
   *
   * @param context
   * @param schemaContent
   * @throws Exception
   */
  private boolean getStructSchema(Context context, ISchemaPluginContext schemaPluginContext, byte[] schemaContent, ISchemaJsonVisitor... scmVisitor) throws Exception {
    SchemaResult result = SchemaResult.parseSchemaResult(this, context, schemaContent, false, schemaPluginContext);
    if (!result.isSuccess()) {
      return false;
    }
    ISchema parseResult = result.getParseResult();
    com.alibaba.fastjson.JSONObject schema = SchemaResult.create(parseResult, schemaContent).toJSON();
    for (ISchemaJsonVisitor visitor : scmVisitor) {
      visitor.process(schema);
    }
    this.setBizResult(context, schema);
    return true;
  }

  /**
   * ????????????????????????json??????????????????(?????????????????????)
   *
   * @param context
   * @throws Exception
   */
  public void doGetFields(Context context) throws Exception {
    Application app = new Application();
    app.setAppId(this.getAppDomain().getAppid());
    UploadResource schemaResource = getAppSchema(this, app);

    SchemaResult schema = SchemaResult.parseSchemaResult(this, context, schemaResource.getContent(), /* shallValidate */
      false, createSchemaPlugin(this.getCollectionName()));

    this.setBizResult(context, schema.toJSON());
  }

  /**
   * ??????Schema xml???????????????????????????
   *
   * @param context
   * @throws Exception
   */
  public void doGetFieldsBySnapshotId(Context context) throws Exception {

    SchemaResult schema = parseSchemaResultWithPluginCfg(this.getCollectionName(), this
      , context, SaveFileContentAction.getResContent(this, context));

    this.setBizResult(context, schema.toJSON());
  }

  /**
   * ?????????Collection?????????plugin
   *
   * @param collection
   * @return
   */
  public static ISchemaPluginContext createSchemaPlugin(String collection) {
    IPluginStore<FieldTypeFactory> fieldTypePluginStore = TIS.getPluginStore(collection, FieldTypeFactory.class);
    Objects.requireNonNull(fieldTypePluginStore, "fieldTypePluginStore can not be null");
    final List<FieldTypeFactory> plugins = fieldTypePluginStore.getPlugins();

    final Set<String> loadedFieldTypePlugins = plugins.stream()
      .filter(p -> p.forStringTokenizer()).map((p) -> p.identityValue()).collect(Collectors.toSet());

    return new ISchemaPluginContext() {
      @Override
      public List<FieldTypeFactory> getFieldTypeFactories() {
        return plugins;
      }

      @Override
      public FieldTypeFactory findFieldTypeFactory(String name) {
        return fieldTypePluginStore.find(name, false);
      }

      @Override
      public boolean isTokenizer(String typeName) {
        return loadedFieldTypePlugins.contains(typeName);
      }
    };

  }

  public static SchemaResult parseSchemaResultWithPluginCfg(
    String collection, IMessageHandler msgHandler, Context context, byte[] resContent) throws Exception {
    final Map<String, Boolean> pluginTypeAddedMap = Maps.newHashMap();
    ISchemaPluginContext schemaPlugin = createSchemaPlugin(collection);


    SchemaResult schemaResult = SchemaResult.parseSchemaResult(
      msgHandler, context, resContent, false, schemaPlugin
      , (cols, sResult) -> {
        boolean pluginTypeAdded;
        String identityNameVal = null;
        List<FieldTypeFactory> fieldTypeFactories = schemaPlugin.getFieldTypeFactories();
        for (FieldTypeFactory plugin : fieldTypeFactories) {
          identityNameVal = plugin.identityValue();
          if (!(pluginTypeAdded = sResult.containType(identityNameVal))) {
            sResult.addFieldType(identityNameVal
              , SolrFieldsParser.parseFieldType(identityNameVal, identityNameVal, plugin.forStringTokenizer()));
          }
          pluginTypeAddedMap.put(identityNameVal, pluginTypeAdded);
        }
      });

    schemaResult.content = XModifier.modifySchemaContent(schemaResult.content, (document2, modifier) -> {
      // FieldTypeFactory fieldTypeFactory = null;
      for (Map.Entry<String, Boolean> entry : pluginTypeAddedMap.entrySet()) {
        if (!entry.getValue()) {
//          fieldTypeFactory = fieldTypePluginStore.find(entry.getKey());
//          Objects.requireNonNull(fieldTypeFactory, "the name:" + entry.getKey() + " relevant fieldTypeFactory can not be null");
          // fieldTypeFactory.
          modifier.addModify(String.format("/types/fieldType[@name='%s']/@class", entry.getKey()), "plugin:" + entry.getKey());
          //precisionStep="0" positionIncrementGap="0"
          //modifier.addModify(String.format("/types/fieldType[@name='%s']/@precisionStep", entry.getKey()), "0");
          modifier.addModify(String.format("/types/fieldType[@name='%s']/@positionIncrementGap", entry.getKey()), "0");
        }
      }

    });
    return schemaResult;
  }

  interface ISchemaJsonVisitor {

    void process(com.alibaba.fastjson.JSONObject schema);
  }


  /**
   * @return
   */
  private static UploadResource getAppSchema(BasicModule module, Application app) {
    final Integer publishSnapshotId = getPublishSnapshotId(module.getServerGroupDAO(), app);
    SnapshotDomain snapshot = module.getSnapshotViewDAO().getView(publishSnapshotId);
    // Snapshot snapshot = module.getSnapshotDAO().selectByPrimaryKey(publishSnapshotId);
    UploadResource uploadRes = snapshot.getSolrSchema();// module.getUploadResourceDAO().selectByPrimaryKey(snapshot.getResSchemaId());
    return uploadRes;
  }

  // protected static final SolrFieldsParser fieldsParser;
  //
  // static {
  // fieldsParser = new SolrFieldsParser();
  // }
  // public static final SolrFieldsParser schemaFieldParser = new SolrFieldsParser();
  private SchemaResult getPostedSchema(Context context) throws Exception, UnsupportedEncodingException {
    return this.getPostedSchema(context, true);
  }

  /**
   * ???????????????schema ?????????????????????schema
   *
   * @param context
   * @return
   * @throws Exception
   * @throws UnsupportedEncodingException
   */
  protected SchemaResult getPostedSchema(Context context, boolean shallvalidate)
    throws Exception, UnsupportedEncodingException {
    // ???????????????xml?????????????????????
    boolean xmlPost = true;
    String schema = null;
    ISchemaPluginContext schemaPlugin = createSchemaPlugin(this.getCollectionName());
    if (StringUtils.isEmpty(schema = this.getString("content"))) {
      xmlPost = false;
      schema = this.createSchema(schemaPlugin, this.getFormValues(), context);
    }
    // ?????????????????????????????????
    SchemaResult result = SchemaResult.parseSchemaResult(this
      , context, schema.getBytes(getEncode()), shallvalidate, schemaPlugin);
    return result;
  }

  protected String createSchema(ISchemaPluginContext schemaPlugin, UploadSchemaWithRawContentForm schemaForm, Context context) throws Exception {
    return this.createSchema(schemaPlugin, schemaForm, true);
  }

  /**
   * Schema XML?????? --> ???????????? ????????????????????????????????????XML????????????????????????????????????xml????????????????????????
   */
  protected String createSchema(ISchemaPluginContext schemaPlugin
    , UploadSchemaWithRawContentForm schemaForm, boolean shallExecuteDelete) throws Exception {
    Assert.assertNotNull(schemaForm);
    // ????????????????????????????????????
    if (StringUtils.isBlank(schemaForm.getSchemaXmlContent())) {
      throw new IllegalArgumentException("schemaXmlContent can not be null");
    }
    // ????????????
    final byte[] originContent = schemaForm.getSchemaXmlContent().getBytes(getEncode());
    org.w3c.dom.Document document = createDocument(originContent);

    ParseResult parseResult = SolrFieldsParser.parseDocument(document, schemaPlugin, false);
    byte[] modifiedContent = XModifier.modifySchemaContent(originContent, (document2, modifier) -> {
      for (PSchemaField field : parseResult.dFields) {
        // ??????????????????????????????????????????????????????????????????????????????????????????
        if (schemaForm.containsField(field.getName())) {
          //  intersectionKeys.add(field.getName());
          continue;
        }
        if (shallExecuteDelete) {
          if (field.isDynamic()) {
            modifier.addModify("/fields/dynamicField[@name='" + field.getName() + "'](:delete)");
          } else {
            modifier.addModify("/fields/field[@name='" + field.getName() + "'](:delete)");
          }
        }
      }
      updateSchemaXML(parseResult.types, schemaPlugin, schemaForm, document2, modifier);
      DocType docType = new DocType("schema", "solrres://tisrepository/dtd/solrschema.dtd");
      document2.setDocType(docType);
    });

    return new String(modifiedContent, TisUTF8.get());
  }

  /**
   * @param fieldTypes ???????????????????????????
   * @param schemaForm
   * @param document2
   * @param modifier
   */
  private static void updateSchemaXML(Map<String, SolrType> fieldTypes, ISchemaPluginContext schemaPlugin
    , ISchema schemaForm, Document document2, final XModifier modifier) {
    SolrType type = null;
    String fieldTypeRef = null;
    FieldTypeFactory fieldTypeFactory = null;

    String pluginName = null;
    for (ISchemaField field : schemaForm.getSchemaFields()) {

      modifySchemaProperty(modifier, field, "type", (fieldTypeRef = parseSolrFieldType(field)));
      modifySchemaProperty(modifier, field, "stored", field.isStored());
      modifySchemaProperty(modifier, field, "indexed", field.isIndexed());
      modifySchemaProperty(modifier, field, "docValues", field.isDocValue());
      modifySchemaProperty(modifier, field, "multiValued", field.isMultiValue());
      type = fieldTypes.get(fieldTypeRef);
      Objects.requireNonNull(type, "fieldName:" + field.getName() + " relevant fieldType can not be null");
      if (type.plugin) {
        pluginName = type.getPluginName();
        fieldTypeFactory = schemaPlugin.findFieldTypeFactory(pluginName);
        Objects.requireNonNull(fieldTypeFactory, "pluginName:" + pluginName + " relevant fieldTypeFactory can not be null");
        fieldTypeFactory.process(document2, modifier);
      }

    }
    if (StringUtils.isNotBlank(schemaForm.getUniqueKey())) {
      modifySchemaProperty("/uniqueKey/text()", schemaForm.getUniqueKey(), modifier);
    } else {
      modifier.deleteUniqueKey();
    }
    if (StringUtils.isNotBlank(schemaForm.getSharedKey())) {
      modifySchemaProperty("/sharedKey/text()", schemaForm.getSharedKey(), modifier);
    } else {
      modifier.deleteSharedKey();
    }
  }

  /**
   * ???????????????field??????
   *
   * @param field
   * @return
   */
  protected static String parseSolrFieldType(ISchemaField field) {
    if (ReflectSchemaFieldType.STRING.literia.equalsIgnoreCase(field.getTisFieldTypeName())
      && StringUtils.isNotBlank(field.getTokenizerType())) {
      return field.getTokenizerType();
    }
    VisualType type = null;
    for (Map.Entry<String, VisualType> entry : TokenizerType.visualTypeMap.entrySet()) {
      type = entry.getValue();
      if (!ReflectSchemaFieldType.STRING.literia.equalsIgnoreCase(field.getTisFieldTypeName())
        && StringUtils.equals(field.getTisFieldTypeName(), entry.getValue().type)) {
        return type.getType();
      }
    }
    return field.getTisFieldTypeName();
  }

  private static void modifySchemaProperty(String key, Object value, XModifier modifier) {
    modifier.addModify(key, String.valueOf(value));
  }

  /**
   * @param modifier
   * @param field
   */
  private static void modifySchemaProperty(XModifier modifier, ISchemaField field, String key, Object value) {
    if (value == null) {
      return;
    }
    if (field.isDynamic()) {
      modifySchemaProperty(String.format("/fields/dynamicField[@name='%s']/@%s", field.getName(), key), value, modifier);
    } else {
      modifySchemaProperty(String.format("/fields/field[@name='%s']/@%s", field.getName(), key), value, modifier);
    }
  }

  private UploadSchemaWithRawContentForm getFormValues() throws Exception {
    String postContent = IOUtils.toString(this.getRequest().getInputStream(), getEncode());
    if (StringUtils.isBlank(postContent)) {
      throw new IllegalStateException("param postContent can not be blank");
    }
    return JSON.parseObject(postContent, UploadSchemaWithRawContentForm.class);
  }

  public static class UploadSchemaWithRawContentForm extends UploadSchemaForm {

    private String dataxName;

    public String getDataxName() {
      return this.dataxName;
    }

    public void setDataxName(String dataxName) {
      this.dataxName = dataxName;
    }

    // ??????SchemaXml ????????????
    private String schemaXmlContent;

    public String getSchemaXmlContent() {
      return schemaXmlContent;
    }

    public void setSchemaXmlContent(String schemaXmlContent) {
      this.schemaXmlContent = schemaXmlContent;
    }
  }

  public static class VisualizingSchemaForm {
    private UploadSchemaWithRawContentForm visualizingForm;
    private Savefilecontent meta;

    public UploadSchemaWithRawContentForm getVisualizingForm() {
      Objects.requireNonNull(this.visualizingForm, "visualizingForm of type UploadSchemaWithRawContentForm can not be null");
      return visualizingForm;
    }

    public void setVisualizingForm(UploadSchemaWithRawContentForm visualizingForm) {
      this.visualizingForm = visualizingForm;
    }

    public Savefilecontent getMeta() {
      Objects.requireNonNull(this.meta, "meta of type Savefilecontent can not be null");
      return meta;
    }

    public void setMeta(Savefilecontent meta) {
      this.meta = meta;
    }
  }

  /**
   * ????????????core schema?????? baisui
   *
   * @param context
   * @throws Exception
   */
  @SuppressWarnings("all")
  public void doModifedSchema(Context context) throws Exception {
    SchemaResult schema = getPostedSchema(context);
    if (!schema.isSuccess()) {
      return;
    }
  }


  /**
   * ????????????schema???????????????????????????????????????<br>
   * ??????????????????
   *
   * @param context
   * @throws Exception
   */
  @Func(value = PermissionConstant.APP_ADD)
  public void doGotoSolrAppCreateConfirm(Context context) throws Exception {
    this.errorsPageShow(context);
    // ????????????schema?????????
    CreateIndexConfirmModel confiemModel = parseJsonPost(CreateIndexConfirmModel.class);
    SchemaResult schemaParse = parseSchema(context, ISchemaPluginContext.NULL, confiemModel);
    LuceneVersion ver = confiemModel.parseTplVersion();
//    if (schemaParse.success) {
//      // ???????????????????????????
//      // TODO ?????????????????????
//      // TISZkStateReader zookeeper = this.getZkStateReader();
//      // Optional<CoreNode> cn = zookeeper.getCoreNodeCandidate(ver);
//      // if (cn.isPresent()) {
//      // this.setBizResult(context, cn.get());
//      // }
//    } else {
//      // this.addErrorMessage(context, "Schema????????????");
//    }
  }

  protected SchemaResult parseSchema(Context context, ISchemaPluginContext schemaPlugin, CreateIndexConfirmModel confiemModel) throws Exception, UnsupportedEncodingException {
    String schemaContent = null;
    SchemaResult schemaParse = null;
    if (!confiemModel.isExpertModel()) {
      // ????????????
      schemaContent = createSchema(schemaPlugin, confiemModel.getStupid().getModel(), context);
      if (!this.validateStupidContent(context, confiemModel.getStupid().getModel())) {
        // ????????????
        schemaParse = SchemaResult.create(null, null);
        return schemaParse.faild();// = false;
        // return schemaParse;
      }
    } else {
      // ????????????
      schemaContent = confiemModel.getExpert().getXml();
    }
    schemaParse = SchemaResult.parseSchemaResult(this
      , context, schemaContent.getBytes(getEncode()), true, schemaPlugin);
    return schemaParse;
  }

  @SuppressWarnings("all")
  protected boolean appendNewSchema(Context context, ISchemaPluginContext schemaPlugin, byte[] content, Application app) throws UnsupportedEncodingException, JSONException {
    if (content == null) {
      throw new NullPointerException("param content can not be null");
    }
    Integer publishSnapshotId = null;
    final ServerGroupCriteria gquery = new ServerGroupCriteria();
    final RunEnvironment runtime = RunEnvironment.getSysRuntime();
    gquery.createCriteria().andAppIdEqualTo(app.getAppId()).andRuntEnvironmentEqualTo(runtime.getId()).andGroupIndexEqualTo((short) 0);
    for (ServerGroup group : this.getServerGroupDAO().selectByExample(gquery)) {
      publishSnapshotId = group.getPublishSnapshotId();
      break;
    }
    if (publishSnapshotId == null) {
      throw new IllegalStateException("app:" + app.getProjectName() + " publishSnapshotId can not be null");
    }
    IUser user = this.getUser();
    // new
    Long usrId = Long.parseLong(user.getId());
    // Long(this.getCurrentIsv().getId());
    CreateSnapshotResult result = createNewSnapshot(context, schemaPlugin, this.getSnapshotViewDAO().getView(publishSnapshotId)
      , ConfigFileReader.FILE_SCHEMA, content, this, new IMessageHandler() {

        @Override
        public void errorsPageShow(Context context) {
        }

        @Override
        public void addActionMessage(Context context, String msg) {
          SchemaAction.this.addActionMessage(context, msg);
        }

        @Override
        public void setBizResult(Context context, Object result) {
          SchemaAction.this.setBizResult(context, result);
        }

        @Override
        public void addErrorMessage(Context context, String msg) {
          SchemaAction.this.addErrorMessage(context, msg);
        }
      }, StringUtils.EMPTY, usrId, user.getName());
    if (!result.isSuccess()) {
      List<String> errorMsgList = (List<String>) context.get(BasicModule.ACTION_ERROR_MSG);
      StringBuffer err = new StringBuffer();
      if (errorMsgList != null) {
        for (String e : errorMsgList) {
          err.append(e).append("<br/>");
        }
      }
      JSONObject errors = new JSONObject();
      errors.put("code", 300);
      errors.put("reason", err.toString());
      context.put("query_result", errors);
      return false;
    }
    ServerGroup record = new ServerGroup();
    record.setPublishSnapshotId(result.getNewId());
    // .andGroupIndexEqualTo((short) 0);
    if (this.getServerGroupDAO().updateByExampleSelective(record, gquery) < 0) {
      throw new IllegalStateException("app:" + app.getProjectName() + " upate getServerGroupDAO have not success");
    }
    return true;
  }

  private static Integer createNewResource(Context context, ISchemaPluginContext schemaPlugin, final byte[] uploadContent, final String md5, PropteryGetter fileGetter
    , IMessageHandler messageHandler, RunContext runContext) throws SchemaFileInvalidException {
    UploadResource resource = new UploadResource();
    resource.setContent(uploadContent);
    resource.setCreateTime(new Date());
    resource.setResourceType(fileGetter.getFileName());
    resource.setMd5Code(md5);
    ConfigFileValidateResult validateResult = fileGetter.validate(schemaPlugin, resource);
    // ??????????????????????????????????????????DTD?????????
    if (!validateResult.isValid()) {
      messageHandler.addErrorMessage(context, "??????????????????DTD?????????XML??????????????????????????????????????????<br/>???&lt;!DOCTYPE schema SYSTEM &quot;solrres://tisrepository/dtd/solrschema.dtd&quot;&gt;???<br/>");
      messageHandler.addErrorMessage(context, validateResult.getValidateResult());
      throw new SchemaFileInvalidException(validateResult.getValidateResult());
    }
    return runContext.getUploadResourceDAO().insertSelective(resource);
  }

  public static CreateSnapshotResult createNewSnapshot(Context context, ISchemaPluginContext schemaPlugin, final SnapshotDomain domain, PropteryGetter fileGetter
    , byte[] uploadContent, RunContext runContext, IMessageHandler messageHandler, String memo, Long userId, String userName) {
    CreateSnapshotResult createResult = new CreateSnapshotResult();
    try {
      // final byte[] uploadContent = content.getContentBytes();
      final String md5 = ConfigFileReader.md5file(uploadContent);
      // ????????????????????????
      try {
        Integer newResId = createNewResource(context, schemaPlugin, uploadContent, md5, fileGetter, messageHandler, runContext);
        final Snapshot snapshot = fileGetter.createNewSnapshot(newResId, domain.getSnapshot());
        snapshot.setMemo(memo);
        createResult.setNewSnapshotId(createNewSnapshot(snapshot, memo, runContext, userId, userName));
        snapshot.setSnId(createResult.getNewId());
        context.put("snapshot", snapshot);
      } catch (SchemaFileInvalidException e) {
        return createResult;
      }
    } finally {
      // try {
      // reader.close();
      // } catch (Throwable e) {
      // }
    }
    createResult.setSuccess(true);
    return createResult;
  }

  protected CreateAppResult createNewApp(Context context, AddAppAction.ExtendApp app
    , int publishSnapshotId, byte[] schemaContent, boolean justValidate) throws Exception {
    IAfterApplicationCreate afterAppCreate = null;
    if (justValidate) {
      afterAppCreate = (newAppid) -> {
        throw new UnsupportedOperationException();
      };
    } else {
      afterAppCreate = (newAppid) -> {
        IUser loginUser = getUser();
        //if (schemaContent != null) {
        Objects.requireNonNull(schemaContent, "schemaContent can not be null");
        SnapshotDomain domain = getSnapshotViewDAO().getView(publishSnapshotId);
        domain.getSnapshot().setAppId(newAppid);
        CreateSnapshotResult snapshotResult = AddAppAction.createNewSnapshot(
          context, // Long.parseLong(loginUser.getId())
          domain, // Long.parseLong(loginUser.getId())
          ConfigFileReader.FILE_SCHEMA, // Long.parseLong(loginUser.getId())
          ISchemaPluginContext.NULL,
          schemaContent, // Long.parseLong(loginUser.getId())
          SchemaAction.this, // Long.parseLong(loginUser.getId())
          StringUtils.EMPTY, -1l, loginUser.getName());
        snapshotResult.setNewAppId(newAppid);
        // if (!snapshotResult.isSuccess()) {
        if (snapshotResult.isSuccess()) {
          GroupAction.createGroup(RunEnvironment.DAILY, AddAppAction.FIRST_GROUP_INDEX, newAppid, snapshotResult.getNewId(), getServerGroupDAO());
          GroupAction.createGroup(RunEnvironment.ONLINE, AddAppAction.FIRST_GROUP_INDEX, newAppid, snapshotResult.getNewId(), getServerGroupDAO());
        }
        return snapshotResult;
        //}
        //}
      };
    }

    return createNewApp(context, app, justValidate, afterAppCreate
      , SchemaAction.FIELD_DS_TYPE, new Validator.FieldValidators(Validator.require) {
      }, //
      new Validator.IFieldValidator() {
        @Override
        public boolean validate(IFieldErrorHandler msgHandler, Context context, String fieldKey, String fieldData) {

          if (AddAppAction.SOURCE_TYPE_SINGLE_TABLE.equals(app.getDsType())) {
            String[] tabCascadervalues = app.getTabCascadervalues();
            if (tabCascadervalues == null) {
              msgHandler.addFieldError(context, fieldKey, "????????????????????????");
              return false;
            }
          } else if (AddAppAction.SOURCE_TYPE_DF.equals(app.getDsType())) {
            String workflowName = app.getWorkflow();
            if (StringUtils.isEmpty(workflowName)) {
              msgHandler.addFieldError(context, fieldKey, "????????????????????????");
              return false;
            }
          } else {
            msgHandler.addFieldError(context, fieldKey, "dsType:" + app.getDsType() + " is not illegal");
            return false;
            // throw new IllegalStateException("dsType:" + app.getDsType() + " is not illegal");
          }

          return true;
        }
      });
  }

  private static // BasicModule module
  Integer createNewSnapshot(// BasicModule module
                            final Snapshot snapshot, // BasicModule module
                            final String memo, // BasicModule module
                            RunContext runContext, // BasicModule module
                            Long userid, String userName) {
    Integer newId;
    snapshot.setSnId(null);
    snapshot.setUpdateTime(new Date());
    snapshot.setCreateTime(new Date());
    try {
      snapshot.setCreateUserId(userid);
    } catch (Throwable e) {
      snapshot.setCreateUserId(0l);
    }
    snapshot.setCreateUserName(userName);
    // final String memo = this.getString("memo");
    if (StringUtils.isNotEmpty(memo)) {
      snapshot.setMemo(memo);
    }
    // ?????????????????????
    newId = runContext.getSnapshotDAO().insertSelective(snapshot);
    if (newId == null) {
      throw new IllegalArgumentException(" have not create a new snapshot id");
    }
    return newId;
  }

  public static class CreateAppResult {
    private Integer newAppId;

    private boolean success = false;

    public Integer getNewAppId() {
      return newAppId;
    }

    public void setNewAppId(Integer newAppId) {
      this.newAppId = newAppId;
    }

    public boolean isSuccess() {
      return success;
    }

    public <T extends CreateAppResult> T setSuccess(boolean success) {
      this.success = success;
      return (T) this;
    }
  }

  public static class CreateSnapshotResult extends CreateAppResult {

    private Integer newSnapshotId;

    public Integer getNewId() {
      return newSnapshotId;
    }

    public void setNewSnapshotId(Integer newId) {
      this.newSnapshotId = newId;
    }

  }


  public static final DocumentBuilderFactory schemaDocumentBuilderFactory = DocumentBuilderFactory.newInstance();

  static {
    // ????????????schema????????????
    schemaDocumentBuilderFactory.setValidating(false);
    // schemaDocumentBuilderFactory.setSchema(schema)
  }

  private static org.w3c.dom.Document createDocument(byte[] schema) throws Exception {
    // javax.xml.parsers.DocumentBuilderFactory dbf =
    // DocumentBuilderFactory
    // .newInstance();
    // dbf.setValidating(false);
    // // dbf.setXIncludeAware(false);
    // dbf.setNamespaceAware(true);
    DocumentBuilder builder = schemaDocumentBuilderFactory.newDocumentBuilder();
    builder.setEntityResolver(new EntityResolver() {

      public InputSource resolveEntity(String publicId, String systemId) throws SAXException, IOException {
        InputSource source = new InputSource();
        source.setCharacterStream(new StringReader(""));
        return source;
      }
    });
    ByteArrayInputStream reader = null;
    try {
      reader = new ByteArrayInputStream(schema);
      org.w3c.dom.Document document = builder.parse(reader);
      return document;
    } finally {
      IOUtils.closeQuietly(reader);
    }
  }

  public static final String KEY_FILE_CONTENT = "filecontent";

  // protected String getContent(Context context)
  // throws UnsupportedEncodingException {
  // String configContent = (String) context.get(KEY_FILE_CONTENT);
  // SnapshotDomain snapshot = getSnapshot(context);
  //
  // if (configContent != null) {
  // return configContent;
  // }
  //
  // return new String(this.getSolrDependency().getContent(snapshot),
  // "utf8");
  // }
  public static Integer getPublishSnapshotId(IServerGroupDAO groupDAO, Application app) {
    if (app == null) {
      throw new IllegalArgumentException("app can not be null");
    }
    final RunEnvironment runtime = RunEnvironment.getSysRuntime();
    ServerGroupCriteria sgCriteria = new ServerGroupCriteria();
    sgCriteria.createCriteria().andAppIdEqualTo(app.getAppId())
      .andRuntEnvironmentEqualTo(runtime.getId()).andGroupIndexEqualTo((short) 0);
    List<ServerGroup> sGroupList = groupDAO.selectByExample(sgCriteria);
    for (ServerGroup group : sGroupList) {
      return group.getPublishSnapshotId();
    }
    throw new IllegalStateException("app:" + app.getAppId() + " can not retrive group");
  }

  // protected PropteryGetter getSolrDependency() {
  // return ConfigFileReader.FILE_SCHEMA;
  // }

  /**
   * ????????????????????????
   *
   * @param context
   * @throws JSONException
   * @throws IOException
   */
  public void doModifyDataSource(Context context) throws Exception, IOException {
    String step = this.getString("step");
    // step == 3 ??????????????????????????????????????????schema
    // step == 5 ???????????????????????????????????????????????????schema
    // if ("5".equals(step)) {
    // modifyDataSourceStep5(context);
    // } else if ("3".equals(step)) {
    // modifyDataSourceStep3(context);
    // }
  }

  // private void modifyDataSourceStep3(Context context) throws Exception
  // {
  // NewAppInfo appinfo = this.getAppinfoFromTair();
  // IDataSourceManage dataSource;
  // Integer aid = appinfo.getAppId();
  // Application app = this.getApplicationDAO().selectByPrimaryKey(aid);
  // dataSource = AbstractDataSourceManage.createDataSource(this,
  // appinfo.getDataType());
  // // 3.??????????????????tair??????
  // dataSource.putToTair(appinfo);
  // // 4.????????????datatype????????????????????????????????????(rds_table,odps,tddl)
  // ApplicationExtend appExtend = this.getApplicationExtendDAO()
  // .selectByAppId(aid);
  // dataSource = AbstractDataSourceManage.createDataSource(this,
  // appExtend.getSourceType());
  // dataSource.deleteDataSource(aid);
  // // 5.??????application_extend
  // updataApplicationExtend(appinfo, aid);
  // // 6.????????????????????????????????????(rds_table,odps,tddl)
  // dataSource = AbstractDataSourceManage.createDataSource(this,
  // appinfo.getDataType());
  // dataSource.insertDataSource(appinfo, aid);
  // // 7.??????t_service???
  // updateTservice(app);
  // // 8.??????zk
  // TerminatorZooKeeper zooker = this.getJstZooKeeper();
  // zooker.setData(
  // ConstantUtil.LASTUPDAATE_ZK_PATH,
  // String.valueOf(
  // ManageUtils.formatDateYYYYMMddHHmmss(Long
  // .valueOf(System.currentTimeMillis())))
  // .getBytes());
  // context.put("query_result", "{\"code\":200}");
  // }
  // private void modifyDataSourceStep5(Context context) throws Exception
  // {
  // Isv isv = this.getCurrentIsv();
  // NewAppInfo appinfo = this.getAppinfoFromTair();
  // IDataSourceManage dataSource;
  // Integer aid = appinfo.getAppId();
  // Application app = this.getApplicationDAO().selectByPrimaryKey(aid);
  // if (!appendNewSchema(context,
  // appinfo.getSchemaXml().getBytes(Charset.forName(getEncode())),
  // app)) {
  // return;
  // }
  //
  // // 4.????????????datatype????????????????????????????????????(rds_table,odps,tddl)
  // ApplicationExtend appExtend = this.getApplicationExtendDAO()
  // .selectByAppId(aid);
  // dataSource = AbstractDataSourceManage.createDataSource(this,
  // appExtend.getSourceType());
  // dataSource.deleteDataSource(aid);
  // // 5.??????application_extend
  // updataApplicationExtend(appinfo, aid);
  // // 6.????????????????????????????????????(rds_table,odps,tddl)
  // dataSource = AbstractDataSourceManage.createDataSource(this,
  // appinfo.getDataType());
  // dataSource.insertDataSource(appinfo, aid);
  // IAppManage appManage = AbstractAppManage.createAppManage(this,
  // appinfo.getDataType());
  // if (!this.isCoreExisted(app.getProjectName())) {//
  // ???????????????????????????????????????schema??????
  // context.put("query_result", "{\"code\":600,\"aid\":" + aid + "}");
  // return;
  // }
  // if (appManage.hasAnyDumpTaskExecuting(app.getProjectName())) {
  // context.put("query_result",
  // "{\"code\":300,\"desc\":\"????????????????????????????????????????????????\"}");
  // return;
  // }
  // // 7.??????t_service???
  // updateTservice(app);
  // // 8.??????zk
  // TerminatorZooKeeper zooker = this.getJstZooKeeper();
  // zooker.setData(
  // ConstantUtil.LASTUPDAATE_ZK_PATH,
  // String.valueOf(
  // ManageUtils.formatDateYYYYMMddHHmmss(Long
  // .valueOf(System.currentTimeMillis())))
  // .getBytes());
  // // 7.????????????schema
  // String updateSchemaResp = appManage.updateSchema(app);
  // // 8.?????????????????????tair?????????????????????????????????
  // appManage.setTairTriggerLock(aid, isv.getNickName());
  // if (StringUtils.isNotBlank(updateSchemaResp)) {
  // try {
  // JSONObject obj = new JSONObject(updateSchemaResp);
  // if (obj.getBoolean("success")) {
  // context.put("query_result", "{\"code\":200,\"taskid\":\""
  // + obj.getLong("taskid") + "\",\"aid\":\"" + aid
  // + "\",\"locktime\":\""
  // + ConstantUtil.ENSPIRETIME_LOCK + "\"}");
  // } else {
  // context.put("query_result", "{\"code\":1005,\"desc\":\""
  // + obj.getString("reason") + "\"}");
  // }
  // } catch (Exception e) {
  // log.info("JSON???????????????" + updateSchemaResp);
  // context.put("query_result",
  // "{\"code\":1006,\"desc\":\"????????????????????????\"}");
  // }
  //
  // } else {
  // log.error("[function=doModifedSchema]create app failed snapshot is
  // null");
  // context.put("query_result", new JSONObject(
  // "{\"code\":1007,\"desc\":\"??????snapshot??????\"}"));
  // }
  // }
  // private void updataApplicationExtend(NewAppInfo appinfo, Integer aid)
  // {
  // Calendar c = Calendar.getInstance();
  // Date date = c.getTime();
  // ApplicationExtendCriteria appExtCriteria = new
  // ApplicationExtendCriteria();
  // appExtCriteria.createCriteria().andAIdEqualTo(aid);
  // ApplicationExtend appExtend = this.getApplicationExtendDAO()
  // .selectByAppId(aid);
  // appExtend.setGmtModified(date);
  // appExtend.setSourceType(StringUtils.lowerCase(appinfo.getDataType()));
  // appExtend.setMaxDocCount(appinfo.getQuota());
  // appExtend.setMaxDumpCount(appinfo.getQuota());
  // appExtend.setMaxPvCount(appinfo.getQps());
  // this.getApplicationExtendDAO().updateByExampleSelective(appExtend,
  // appExtCriteria);
  // }
  // ??????t_service???
  // private void updateTservice(Application app) throws Exception {
  // Date date = Calendar.getInstance().getTime();
  // Instance ins = this.getInstanceDAO().selectByPrimaryKey(
  // app.getInstanceId());
  // TServiceCriteria tCriteria = new TServiceCriteria();
  // tCriteria.createCriteria().andIsvServiceNameEqualTo(
  // app.getProjectName());
  // List<TService> tList =
  // this.getTServiceDAO().selectByExample(tCriteria);
  // if (tList.size() > 0) {
  // TService record = tList.get(0);
  // record.setGmtModified(date);// ????????????
  // ServerGroupCriteria sgCriteria = new ServerGroupCriteria();
  // sgCriteria
  // .createCriteria()
  // .andAppIdEqualTo(app.getAppId())
  // .andRuntEnvironmentEqualTo(ManageUtils.getRuntime().getId());
  // List<ServerGroup> sGroupList = this.getServerGroupDAO()
  // .selectByExample(sgCriteria);
  // if (sGroupList.size() > 0) {
  // Snapshot snapshot = this.getSnapshotDAO().selectByPrimaryKey(
  // sGroupList.get(0).getPublishSnapshotId());
  // UploadResource uploadRes = this.getUploadResourceDAO()
  // .selectByPrimaryKey(snapshot.getResSchemaId());
  // String shardkey = new AppManageAction().getShardkey(new String(
  // uploadRes.getContent()));
  // record.setSharedKey(shardkey);// ?????????
  // }
  // if ("Y".equals(ins.getIsLock()) || "Y".equals(ins.getIsRelease())) {
  // record.setStatus(new Byte("1"));// ?????????0?????? 1??????
  // } else {
  // record.setStatus(new Byte("0"));// ?????????0?????? 1??????
  // }
  // this.getTServiceDAO().updateByExampleSelective(record, tCriteria);
  // log.info("update t_service success!");
  // }
  //
  // }

  /**
   * @param args
   * @throws Exception
   */
  public static void main(String[] args) throws Exception {
    // for (VisualType type : new
    // HashSet<VisualType>(TokenizerType.visualTypeMap.values())) {
    // System.out.println(type.type + "," + type.ranageQueryAware);
    // }
    // Calendar c = Calendar.getInstance();
    // Date date = c.getTime();
    // System.out.println("time:"+date.toString());
    // System.out.println("time:"+c.getTimeInMillis());
    // System.out.println(System.currentTimeMillis()/1000);
    // org.w3c.dom.Document document = createDocument(FileUtils
    // .readFileToString(new File("D:\\home\\schema.xml")));
    // SAXReader saxReader = new SAXReader();
    // saxReader.setDocumentFactory(new DOMDocumentFactory());
    // // ??????saxReader???read????????????????????????????????????inputStream????????????????????????????????????file???????????????
    // // Document document = saxReader.read(inputStream);
    // DOMDocument document4j = (DOMDocument) saxReader.read(new File(
    // "D:\\home\\schema.xml"));
    //
    // XModifier modifier = new XModifier(document4j);
    // modifier.addModify(
    // "/schema/types/fieldType[@name='singleString']/@class",
    // "java.lang.String");
    // modifier.addModify("/schema/types/fieldType[@name='kkkkkkkk']/@class",
    // "ddddddd");
    // modifier.addModify("/schema/types/fieldType[@name='xxxxxx'][@class='java.lang.String']");
    // modifier.addModify(
    // "/schema/types/fieldType[@name='singleStringggg']/@xxxxx",
    // "xxxxxx");
    // modifier.modify();
    // NodeList nl = document.getChildNodes().item(1).getChildNodes();
    // Node n = null;
    // for (int i = 0; i < nl.getLength(); i++) {
    // n = nl.item(i);
    // System.out.println(String.format("xxx %s,%s",
    // new String[] { n.getNodeName(), n.getNodeValue() }));
    // }
    // modifier.modify();
    // TransformerFactory tf = TransformerFactory.newInstance();
    // Transformer transformer = tf.newTransformer();
    //
    // transformer.setOutputProperty(OutputKeys.DOCTYPE_SYSTEM,
    // transformer.setOutputProperty(OutputKeys.INDENT, "yes");
    // transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION,
    // "no");
    // StringWriter writer = new StringWriter();
    // transformer
    // .transform(new DOMSource(document), new StreamResult(writer));
    // // System.out.println(writer.getBuffer());
    // DOMBuilder domBuilder = new DOMBuilder();
    // // ??????xml????????????---????????????????????????????????????xml??????????????????????????????
    // Format format = Format.getRawFormat();
    //
    // // ??????????????????************************ ??????schema????????????
    // // ??????????????????????????????????????????
    //
    // org.jdom2.Document doc = domBuilder.build(document4j);
    //
    // // ??????docType??????
    // DocType docType = new DocType("schema",
    // doc.setDocType(docType);
    // XMLOutputter xmlout = new XMLOutputter(format);
    // // ??????xml????????????
    // xmlout.setFormat(format.setEncoding("utf8"));
    //
    // ByteArrayOutputStream byteRsp = new ByteArrayOutputStream();
    //
    // xmlout.output(doc, byteRsp);
    //
    // System.out.println(byteRsp.toString("utf8"));
  }

//  public static class NumericVisualType extends VisualType {
//
//    public static NumericVisualType create(VisualType type) {
//      NumericVisualType result = new NumericVisualType(type.getType());
//      // result.setRangeEnable(rangeEnable);
//      return result;
//    }
//
//    // private boolean rangeEnable;
//
//    private NumericVisualType(String type) {
//      super(type, false);
//    }
//
//  }
}
