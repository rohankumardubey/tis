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
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.koubei.web.tag.pager.Pager;
import com.qlangtech.tis.IPluginEnum;
import com.qlangtech.tis.TIS;
import com.qlangtech.tis.extension.*;
import com.qlangtech.tis.extension.impl.PropertyType;
import com.qlangtech.tis.extension.impl.SuFormProperties;
import com.qlangtech.tis.extension.model.UpdateCenter;
import com.qlangtech.tis.extension.model.UpdateSite;
import com.qlangtech.tis.extension.util.PluginExtraProps;
import com.qlangtech.tis.install.InstallState;
import com.qlangtech.tis.install.InstallUtil;
import com.qlangtech.tis.manage.common.Config;
import com.qlangtech.tis.manage.common.ConfigFileContext;
import com.qlangtech.tis.manage.common.HttpUtils;
import com.qlangtech.tis.manage.common.Option;
import com.qlangtech.tis.offline.module.manager.impl.OfflineManager;
import com.qlangtech.tis.plugin.IdentityName;
import com.qlangtech.tis.plugin.annotation.FormFieldType;
import com.qlangtech.tis.plugin.ds.DataSourceFactory;
import com.qlangtech.tis.runtime.module.action.BasicModule;
import com.qlangtech.tis.runtime.module.misc.IMessageHandler;
import com.qlangtech.tis.util.*;
import com.qlangtech.tis.workflow.pojo.DatasourceDb;
import com.qlangtech.tis.workflow.pojo.DatasourceDbCriteria;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.struts2.convention.annotation.InterceptorRef;
import org.apache.struts2.convention.annotation.InterceptorRefs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.ReflectionUtils;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * @author ?????????baisui@qlangtech.com???
 * @date 2020/04/13
 */
@InterceptorRefs({@InterceptorRef("tisStack")})
public class PluginAction extends BasicModule {
  private static final Logger logger = LoggerFactory.getLogger(PluginAction.class);
  private OfflineManager offlineManager;

  static {

    PluginItems.addPluginItemsSaveObserver((new PluginItems.PluginItemsSaveObserver() {
      // ??????Assemble????????????pluginStore?????????
      @Override
      public void afterSaved(PluginItems.PluginItemsSaveEvent event) {
        final String extendPoint = event.heteroEnum.getExtensionPoint().getName();
        // @see "com.qlangtech.tis.fullbuild.servlet.TaskStatusServlet"
        notifyPluginUpdate2AssembleNode(DescriptorsJSON.KEY_EXTEND_POINT + "=" + extendPoint, "pluginStore");
      }
    }));
  }

  private static void notifyPluginUpdate2AssembleNode(String applyParams, String targetResource) {
    try {
      URL url = new URL(Config.getAssembleHttpHost() + "/task_status?" + applyParams);
      HttpUtils.get(url, new ConfigFileContext.StreamProcess<Void>() {
        @Override
        public Void p(int status, InputStream stream, Map<String, List<String>> headerFields) {
          logger.info("has apply clean " + targetResource + " cache by " + applyParams);
          return null;
        }
      });
    } catch (Exception e) {
      logger.warn("apply clean " + targetResource + " cache faild " + e.getMessage());
    }
  }

  /**
   * ????????????????????????
   *
   * @param context
   */
  public void doGetFreshEnumField(Context context) {
    DescriptorField descField = parseDescField();
    List<Descriptor.SelectOption> options = null;
    if (descField.getFieldPropType().typeIdentity() == FormFieldType.SELECTABLE.getIdentity()) {
      options = DescriptorsJSON.getSelectOptions(
        descField.getTargetDesc(), descField.getFieldPropType(), descField.field);
      this.setBizResult(context, options);
    } else if (descField.getFieldPropType().typeIdentity() == FormFieldType.ENUM.getIdentity()) {
      this.setBizResult(context
        , descField.getFieldPropType().getExtraProps().getJSONArray(Descriptor.KEY_ENUM_PROP));
    }
  }

  private static class DescriptorField {
    final String pluginImpl;
    final String field;

    public DescriptorField(String pluginImpl, String field) {
      this.pluginImpl = pluginImpl;
      this.field = field;
    }

    Descriptor getTargetDesc() {
      return TIS.get().getDescriptor(this.pluginImpl);
    }

    PropertyType getFieldPropType() {
      return (PropertyType) getTargetDesc().getPropertyType(this.field);
    }
  }

  private DescriptorField parseDescField() {
    String pluginImpl = this.getString("impl");
    String fieldName = this.getString("field");
    if (StringUtils.isEmpty(pluginImpl)) {
      throw new IllegalArgumentException("param 'impl' can not be null");
    }
    if (StringUtils.isEmpty(fieldName)) {
      throw new IllegalArgumentException("param 'field' can not be null");
    }
    return new DescriptorField(pluginImpl, fieldName);
  }

  /**
   * ???????????????????????????
   *
   * @param context
   */
  public void doGetPluginFieldHelp(Context context) {
    DescriptorField descField = parseDescField();
    // Descriptor targetDesc = TIS.get().getDescriptor(descField.pluginImpl);

    // PropertyType fieldProp = (PropertyType) targetDesc.getPropertyType(fieldName);
    PluginExtraProps.Props props = descField.getFieldPropType().extraProp;
    if (!props.isAsynHelp()) {
      throw new IllegalStateException("plugin:" + descField.pluginImpl + ",field:" + descField.field + " is not support async help content fecthing");
    }
    this.setBizResult(context, props.getAsynHelp());
  }

  /**
   * ????????????????????????
   *
   * @param context
   */
  public void doGetUpdateCenterStatus(Context context) {
    UpdateCenter updateCenter = TIS.get().getUpdateCenter();
    List<UpdateCenter.UpdateCenterJob> jobs = updateCenter.getJobs();
    Collections.sort(jobs, (a, b) -> {
      // ?????????????????????job??????????????????
      return b.id - a.id;
    });
    setBizResult(context, jobs);
  }

  /**
   * ???????????????????????????
   *
   * @param context
   */
  public void doGetInstalledPlugins(Context context) {

    List<String> extendpoint = getExtendpointParam();
    PluginManager pluginManager = TIS.get().getPluginManager();
    JSONArray response = new JSONArray();
    JSONObject pluginInfo = null;
    UpdateSite.Plugin info = null;
    for (PluginWrapper plugin : pluginManager.getPlugins()) {

      pluginInfo = new JSONObject();
      pluginInfo.put("installed", true);
      info = plugin.getInfo();
      if (info != null) {
        // pluginInfo.put("meta", info);
        pluginInfo.put("releaseTimestamp", info.releaseTimestamp);
        pluginInfo.put("excerpt", info.excerpt);
      }

      if (CollectionUtils.isNotEmpty(extendpoint)) {
        if (info == null) {
          continue;
        }

        if (!CollectionUtils.containsAny(info.extendPoints.keySet(), extendpoint)) {
          continue;
        }
        pluginInfo.put("extendPoints", info.extendPoints);
      }

      if (filterPlugin(plugin)) {
        continue;
      }

      pluginInfo.put("name", plugin.getShortName());
      pluginInfo.put("version", plugin.getVersion());
      pluginInfo.put("title", plugin.getDisplayName());
      pluginInfo.put("active", plugin.isActive());
      pluginInfo.put("enabled", plugin.isEnabled());
      // pluginInfo.put("bundled", plugin.isBundled);
      pluginInfo.put("deleted", plugin.isDeleted());
      pluginInfo.put("downgradable", plugin.isDowngradable());
      pluginInfo.put("website", plugin.getUrl());
      List<PluginWrapper.Dependency> dependencies = plugin.getDependencies();
      if (dependencies != null && !dependencies.isEmpty()) {
        Option o = null;
        List<Option> dependencyMap = Lists.newArrayList();
        for (PluginWrapper.Dependency dependency : dependencies) {
          o = new Option(dependency.shortName, dependency.version);
          dependencyMap.add(o);
        }
        pluginInfo.put("dependencies", dependencyMap);
      } else {
        pluginInfo.put("dependencies", Collections.emptyList());
      }
      response.add(pluginInfo);
    }
    this.setBizResult(context, response);
  }

  private boolean filterPlugin(PluginWrapper plugin) {
    return filterPlugin(plugin.getDisplayName(), (plugin.getInfo() != null ? plugin.getInfo().excerpt : null));
  }

  private boolean filterPlugin(String title, String excerpt) {

    List<String> queries = getQueryPluginParam();
    if (CollectionUtils.isEmpty(queries)) {
      return false;
    }
    boolean collect = false;
    for (String searchQ : queries) {
      if ((StringUtils.indexOfIgnoreCase(title, searchQ) > -1
        || (StringUtils.isNotBlank(excerpt) && StringUtils.indexOfIgnoreCase(excerpt, searchQ) > -1))
      ) {
        collect = true;
        break;
      }
    }
    // ??????
    return !collect;
  }

  private List<String> getQueryPluginParam() {
    String[] queries = StringUtils.split(this.getString("query"), " ");
    if (queries == null) {
      return Collections.emptyList();
    }
    return Lists.newArrayList(queries);
  }

  /**
   * ????????????
   *
   * @param context
   */
  public void doInstallPlugins(Context context) {
    JSONArray pluginsInstall = this.parseJsonArrayPost();
    if (pluginsInstall.size() < 1) {
      this.addErrorMessage(context, "??????????????????????????????");
      return;
    }
    long start = System.currentTimeMillis();
    boolean dynamicLoad = true;
    UUID correlationId = UUID.randomUUID();
    UpdateCenter updateCenter = TIS.get().getUpdateCenter();
    List<Future<UpdateCenter.UpdateCenterJob>> installJobs = new ArrayList<>();
    JSONObject willInstall = null;
    String pluginName = null;
    UpdateSite.Plugin plugin = null;
    List<PluginWrapper> batch = new ArrayList<>();
    for (int i = 0; i < pluginsInstall.size(); i++) {
      willInstall = pluginsInstall.getJSONObject(i);
      pluginName = willInstall.getString("name");
      if (StringUtils.isEmpty(pluginName)) {
        throw new IllegalStateException("plugin name can not empty");
      }
      plugin = updateCenter.getPlugin(pluginName);
      Future<UpdateCenter.UpdateCenterJob> installJob = plugin.deploy(dynamicLoad, correlationId, batch);
      installJobs.add(installJob);
    }
    if (dynamicLoad) {
      installJobs.add(updateCenter.addJob(updateCenter.new CompleteBatchJob(batch, start, correlationId)));
    }

    final TIS tis = TIS.get();

    //TODO: ??????????????????????????????
    if (true || !tis.getInstallState().isSetupComplete()) {
      tis.setInstallState(InstallState.INITIAL_PLUGINS_INSTALLING);
      updateCenter.persistInstallStatus();
      new Thread() {
        @Override
        public void run() {
          boolean failures = false;
          INSTALLING:
          while (true) {
            try {
              updateCenter.persistInstallStatus();
              Thread.sleep(500);
              failures = false;
              for (Future<UpdateCenter.UpdateCenterJob> jobFuture : installJobs) {
                if (!jobFuture.isDone() && !jobFuture.isCancelled()) {
                  continue INSTALLING;
                }
                UpdateCenter.UpdateCenterJob job = jobFuture.get();
                if (job instanceof UpdateCenter.InstallationJob && ((UpdateCenter.InstallationJob) job).status instanceof UpdateCenter.DownloadJob.Failure) {
                  failures = true;
                }
              }
            } catch (Exception e) {
              logger.warn("Unexpected error while waiting for initial plugin set to install.", e);
            }
            break;
          }
          updateCenter.persistInstallStatus();
          if (!failures) {

            InstallUtil.proceedToNextStateFrom(InstallState.INITIAL_PLUGINS_INSTALLING);
            // ?????????Assemble????????????uberClassLoader????????????????????????????????????Assemble???????????????????????????
            notifyPluginUpdate2AssembleNode(TIS.KEY_ACTION_CLEAN_TIS + "=true", "TIS");
          }
        }
      }.start();
    }
  }

  /**
   * ????????????????????????????????????
   *
   * @param context
   */
  public void doGetAvailablePlugins(Context context) {

    List<String> extendpoint = getExtendpointParam();
    Pager pager = this.createPager();
    pager.setTotalCount(Integer.MAX_VALUE);
    List<UpdateSite.Plugin> availables = TIS.get().getUpdateCenter().getAvailables();
    if (CollectionUtils.isNotEmpty(extendpoint)) {
      availables = availables.stream().filter((plugin) -> {
        return CollectionUtils.containsAny(plugin.extendPoints.keySet(), extendpoint);
        // return plugin.extendPoints.containsKey(extendpoint.get());
      }).collect(Collectors.toList());
    }

    if (CollectionUtils.isNotEmpty(this.getQueryPluginParam())) {
      availables = availables.stream().filter((plugin) -> {
        return !filterPlugin(plugin.title, plugin.excerpt);
      }).collect(Collectors.toList());
    }

    this.setBizResult(context, new PaginationResult(pager, availables));
  }

  private List<String> getExtendpointParam() {
    return Arrays.asList(this.getStringArray("extendpoint"));
//    return Optional.ofNullable(this.getString("extendpoint"));
  }

  /**
   * @param context
   * @throws Exception
   */
  public void doSwitchExtensionPointShow(Context context) throws Exception {
    boolean open = this.getBoolean("switch");
    TIS tis = TIS.get();
    tis.saveComponent(tis.loadGlobalComponent().setShowExtensionDetail(open));
  }

  public void doGetExtensionPointShow(Context context) throws Exception {
    TIS tis = TIS.get();
    this.setBizResult(context, tis.loadGlobalComponent().isShowExtensionDetail());
  }

  /**
   * @param context
   */
  public void doGetDescriptor(Context context) {
    this.errorsPageShow(context);
    final String displayName = this.getString("name");
    if (StringUtils.isEmpty(displayName)) {
      throw new IllegalArgumentException("request param 'impl' can not be null");
    }
    IPluginEnum hetero = HeteroEnum.of(this.getString("hetero"));
    List<Descriptor<Describable>> descriptors = hetero.descriptors();
    for (Descriptor desc : descriptors) {
      if (StringUtils.equals(desc.getDisplayName(), displayName)) {
        this.setBizResult(context, new DescriptorsJSON(desc).getDescriptorsJSON());
        return;
      }
    }

    // throw new IllegalStateException("displayName:" + displayName + " relevant Descriptor can not be null");
    this.setBizResult(context, Collections.singletonMap("notFoundExtension", hetero.getExtensionPoint().getName()));
    this.addErrorMessage(context, "displayName:" + displayName + " relevant Descriptor can not be null");

  }

  /**
   * @param context
   */
  public void doGetDescsByExtendpoint(Context context) throws Exception {
    List<String> extendpoints = this.getExtendpointParam();
    if (CollectionUtils.isEmpty(extendpoints)) {
      throw new IllegalArgumentException("extendpoints can not be null");
    }

    for (String extend : extendpoints) {
      this.setBizResult(context
        , new DescriptorsJSON(TIS.get().getDescriptorList((Class<Describable>) Class.forName(extend))).getDescriptorsJSON());
      return;
    }

    throw new IllegalArgumentException("extendpoints can not be null");
  }

  /**
   * plugin form ??????????????????????????????????????????
   *
   * @param context
   * @throws Exception
   */
  public void doSubformDetailedClick(Context context) throws Exception {
    List<UploadPluginMeta> pluginsMeta = getPluginMeta();
    List<Describable> plugins = null;
    Map<String, String> execContext = Maps.newHashMap();
    execContext.put("id", this.getString("id"));

    IPluginEnum heteroEnum = null;
    for (UploadPluginMeta meta : pluginsMeta) {
      heteroEnum = meta.getHeteroEnum();
      plugins = heteroEnum.getPlugins(this, meta);
      for (Describable p : plugins) {

        PluginFormProperties pluginFormPropertyTypes = p.getDescriptor().getPluginFormPropertyTypes(meta.getSubFormFilter());
        pluginFormPropertyTypes.accept(new DescriptorsJSON.SubFormFieldVisitor() {
          @Override
          protected void visitSubForm(JSONObject behaviorMeta, SuFormProperties props) {
            JSONObject fieldDataGetterMeta = null;
            JSONArray params = null;
            JSONObject onClickFillData = behaviorMeta.getJSONObject("onClickFillData");
            Objects.requireNonNull(onClickFillData, "onClickFillData can not be null");
            Map<String, Object> fillFieldsData = Maps.newHashMap();
            for (String fillField : onClickFillData.keySet()) {
              fieldDataGetterMeta = onClickFillData.getJSONObject(fillField);
              Objects.requireNonNull(fieldDataGetterMeta, "fillField:" + fillField + " relevant behavier meta can not be null");
              String targetMethod = fieldDataGetterMeta.getString("method");
              params = fieldDataGetterMeta.getJSONArray("params");
              Objects.requireNonNull(params, "params can not be null");
              Class<?>[] paramClass = new Class<?>[params.size()];
              String[] paramsVals = new String[params.size()];
              for (int index = 0; index < params.size(); index++) {
                paramClass[index] = String.class;
                paramsVals[index] = Objects.requireNonNull(execContext.get(params.getString(index))
                  , "param:" + params.getString(index) + " can not be null in context");
              }
              Method method = ReflectionUtils.findMethod(p.getClass(), targetMethod, paramClass);
              Objects.requireNonNull(method, "target method '" + targetMethod + "' of " + p.getClass() + " can not be null");
              fillFieldsData.put(fillField, ReflectionUtils.invokeMethod(method, p, paramsVals));
            }
            // params ????????????spring?????????
            setBizResult(context, fillFieldsData);
          }
        });


        return;
      }
    }
    throw new IllegalStateException("have not set plugin meta");
  }

  public void doGetPluginConfigInfo(Context context) throws Exception {

    HeteroList<?> hList = null;
    List<UploadPluginMeta> plugins = getPluginMeta();

    if (plugins == null || plugins.size() < 1) {
      throw new IllegalArgumentException("param plugin is not illegal");
    }
    com.alibaba.fastjson.JSONObject pluginDetail = new com.alibaba.fastjson.JSONObject();
    com.alibaba.fastjson.JSONArray hlist = new com.alibaba.fastjson.JSONArray();
    pluginDetail.put("showExtensionPoint", TIS.get().loadGlobalComponent().isShowExtensionDetail());
    for (UploadPluginMeta pmeta : plugins) {
      hList = pmeta.getHeteroList(this);
      hlist.add(hList.toJSON());
    }
    pluginDetail.put("plugins", hlist);
    this.setBizResult(context, pluginDetail);
  }


  /**
   * ??????blugin??????
   *
   * @param context
   */
  public void doSavePluginConfig(Context context) throws Exception {
    if (this.getBoolean("errors_page_show")) {
      this.errorsPageShow(context);
    }
    List<UploadPluginMeta> plugins = getPluginMeta();
    JSONArray pluginArray = parseJsonArrayPost();

    UploadPluginMeta pluginMeta = null;
    // JSONObject itemObj = null;
    boolean faild = false;
    List<PluginItems> categoryPlugins = Lists.newArrayList();
    // ????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
    final boolean verify = this.getBoolean("verify");
    PluginItemsParser pluginItemsParser = null;
    for (int pluginIndex = 0; pluginIndex < plugins.size(); pluginIndex++) {
      // items = Lists.newArrayList();
      pluginMeta = plugins.get(pluginIndex);
      // subFormFilter = pluginMeta.getSubFormFilter();
      JSONArray itemsArray = pluginArray.getJSONArray(pluginIndex);
      // hEnum = pluginMeta.getHeteroEnum();
      pluginItemsParser = parsePluginItems(this, pluginMeta, context, pluginIndex, itemsArray, verify);
      if (pluginItemsParser.faild) {
        faild = true;
      }
      categoryPlugins.add(pluginItemsParser.pluginItems);
    }
    if (this.hasErrors(context) || verify) {
      return;
    }
    if (faild) {
      // ???????????????plugin???????????????????????????????????????
      this.addErrorMessage(context, "???????????????????????????");
      return;
    }

    List<Describable> describables = Lists.newArrayList();

    for (PluginItems pi : categoryPlugins) {
      describables.addAll(pi.save(context));
    }
    addActionMessage(context, "??????????????????");
    // ?????????????????????????????????????????????
    if (context.get(IMessageHandler.ACTION_BIZ_RESULT) == null) {
      this.setBizResult(context, describables.stream()
        .filter((d) -> d instanceof IdentityName)
        .map((d) -> ((IdentityName) d).identityValue()).collect(Collectors.toList()));
    }
  }


  public static PluginItemsParser parsePluginItems(BasicModule module, UploadPluginMeta pluginMeta
    , Context context, int pluginIndex, JSONArray itemsArray, boolean verify) {
    context.put(UploadPluginMeta.KEY_PLUGIN_META, pluginMeta);
    PluginItemsParser parseResult = new PluginItemsParser();
    List<Descriptor.PluginValidateResult> items = Lists.newArrayList();
    Optional<IPropertyType.SubFormFilter> subFormFilter = pluginMeta.getSubFormFilter();
    Descriptor.PluginValidateResult validateResult = null;
    IPluginEnum hEnum = pluginMeta.getHeteroEnum();
    //context.put(KEY_VALIDATE_PLUGIN_INDEX, new Integer(pluginIndex));
    PluginItems pluginItems = new PluginItems(module, pluginMeta);
    List<AttrValMap> describableAttrValMapList = AttrValMap.describableAttrValMapList(module, itemsArray, subFormFilter);
    if (pluginMeta.isRequired() && describableAttrValMapList.size() < 1) {
      module.addErrorMessage(context, "?????????'" + hEnum.getCaption() + "'????????????");
    }


    pluginItems.items = describableAttrValMapList;
    parseResult.pluginItems = pluginItems;
    //categoryPlugins.add(pluginItems);
    AttrValMap attrValMap = null;


    for (int itemIndex = 0; itemIndex < describableAttrValMapList.size(); itemIndex++) {
      attrValMap = describableAttrValMapList.get(itemIndex);
      Descriptor.PluginValidateResult.setValidateItemPos(context, pluginIndex, itemIndex);
      if (!(validateResult = attrValMap.validate(context, verify)).isValid()) {
        parseResult.faild = true;
      } else {
        validateResult.setDescriptor(attrValMap.descriptor);
        items.add(validateResult);
      }
    }


    /**===============================================
     * ??????Item?????????identity????????????????????????????????????
     ===============================================*/
    Map<String, Descriptor.PluginValidateResult> identityUniqueMap = Maps.newHashMap();

    Descriptor.PluginValidateResult previous = null;
    if (!parseResult.faild && hEnum.isIdentityUnique()
      && hEnum.getSelectable() == Selectable.Multi
      && (items.size() > 1 || pluginMeta.isAppend())) {

      if (pluginMeta.isAppend()) {
        List<IdentityName> plugins = hEnum.getPlugins(module, pluginMeta);
        for (IdentityName p : plugins) {
          Descriptor.PluginValidateResult r = new Descriptor.PluginValidateResult(new Descriptor.PostFormVals(Collections.emptyMap()), 0, 0);
          r.setDescriptor(((Describable) p).getDescriptor());
          identityUniqueMap.put(p.identityValue(), r);
        }
      }

      for (Descriptor.PluginValidateResult i : items) {
        if ((previous = identityUniqueMap.put(i.getIdentityFieldValue(), i)) != null) {
          previous.addIdentityFieldValueDuplicateError(module, context);
          i.addIdentityFieldValueDuplicateError(module, context);
          return parseResult;
        }
      }
    }
    return parseResult;
  }

  public static class PluginItemsParser {
    public boolean faild = false;
    public PluginItems pluginItems;
  }

  private List<UploadPluginMeta> getPluginMeta() {
    return UploadPluginMeta.parse(this.getStringArray("plugin"));
  }

  /**
   * ??????????????????????????????????????????
   *
   * @return
   */
  @Override
  public boolean isDataSourceAware() {
    //return super.isDataSourceAware();
    List<UploadPluginMeta> pluginMeta = getPluginMeta();
    return pluginMeta.size() == 1 && pluginMeta.stream().findFirst().get().getHeteroEnum() == HeteroEnum.DATASOURCE;
  }

  /**
   * description: ???????????? ???????????? date: 2:30 PM 4/28/2017
   */
  @Override
  public final void addDb(Descriptor.ParseDescribable<DataSourceFactory> dbDesc, String dbName, Context context, boolean shallUpdateDB) {
    createDatabase(this, dbDesc, dbName, context, shallUpdateDB, this.offlineManager);
  }

  public static DatasourceDb createDatabase(BasicModule module, Descriptor.ParseDescribable<DataSourceFactory> dbDesc, String dbName, Context context
    , boolean shallUpdateDB, OfflineManager offlineManager) {
    DatasourceDb datasourceDb = null;
    if (shallUpdateDB) {
      datasourceDb = new DatasourceDb();
      datasourceDb.setName(dbName);
      datasourceDb.setSyncOnline(new Byte("0"));
      datasourceDb.setCreateTime(new Date());
      datasourceDb.setOpTime(new Date());
      datasourceDb.setExtendClass(StringUtils.lowerCase(dbDesc.instance.getDescriptor().getDisplayName()));

      DatasourceDbCriteria criteria = new DatasourceDbCriteria();
      criteria.createCriteria().andNameEqualTo(dbName);
      int exist = module.getWorkflowDAOFacade().getDatasourceDbDAO().countByExample(criteria);
      if (exist > 0) {
        module.addErrorMessage(context, "??????????????????(" + dbName + ")????????????");
        return null;
      }
      /**
       * ?????????????????????????????????
       */
      int dbId = module.getWorkflowDAOFacade().getDatasourceDbDAO().insertSelective(datasourceDb);
      datasourceDb.setId(dbId);
      //module.setBizResult(context, datasourceDb);
    } else {
      // ????????????
      DatasourceDbCriteria dbCriteria = new DatasourceDbCriteria();
      dbCriteria.createCriteria().andNameEqualTo(dbName);
      for (DatasourceDb db : module.getWorkflowDAOFacade().getDatasourceDbDAO().selectByExample(dbCriteria)) {
        datasourceDb = db;
        break;
      }
      Objects.requireNonNull(datasourceDb, "dbName:" + dbName + " relevant datasourceDb can not be null");
    }

    module.setBizResult(context, offlineManager.getDbConfig(module, datasourceDb));
    return datasourceDb;
  }


  @Autowired
  public void setOfflineManager(OfflineManager offlineManager) {
    this.offlineManager = offlineManager;
  }

}
