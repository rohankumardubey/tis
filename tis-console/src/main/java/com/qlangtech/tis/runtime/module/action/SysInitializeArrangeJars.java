/**
 * Copyright (c) 2020 QingLang, Inc. <baisui@qlangtech.com>
 * <p>
 * This program is free software: you can use, redistribute, and/or modify
 * it under the terms of the GNU Affero General Public License, version 3
 * or later ("AGPL"), as published by the Free Software Foundation.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.
 * <p>
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.qlangtech.tis.runtime.module.action;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.qlangtech.tis.manage.common.Option;
import com.qlangtech.tis.util.Memoizer;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

//import org.apache.commons.lang3.concurrent.Memoizer;


/**
 * 重新整理 项目中的jar包，可以使得整个Uber包可以做到最小
 *
 * @author 百岁（baisui@qlangtech.com）
 * @date 2021-03-22 14:59
 */
public class SysInitializeArrangeJars {
  // private static final List<Option> subDirs = Lists.newArrayList("tis-assemble", "solr", "tjs", "tis-collect");

  private static final String tis_builder_api = "tis-builder-api(.*)\\.jar";
  private static final String tis_manage_pojo = "tis-manage-pojo(.*)\\.jar";
  private static final String spring_web = "spring-web-(.*)\\.jar";

  private static final String commons_ = "commons-(.*)\\.jar";

//  private static final List<SubProj> subDirs
//    = Lists.newArrayList( //
//    new SubProj("tis-assemble", "tis-assemble\\.jar", tis_builder_api, tis_manage_pojo, spring_web) //
//    , new SubProj("solr", "solr\\.jar", tis_builder_api, tis_manage_pojo, spring_web)
//    , new SubProj("tjs", "tis\\.jar", tis_builder_api, tis_manage_pojo, spring_web)
//    , new SubProj("tis-collect", "tis-collect\\.jar", tis_builder_api, tis_manage_pojo, spring_web));


  private static final List<SubProj> subDirs
    = Lists.newArrayList( //
    new SubProj("tis-assemble", commons_) //
    , new SubProj("solr", commons_)
    , new SubProj("tjs", commons_)
    , new SubProj("tis-collect", commons_));

  static final Memoizer<String, List<File>> jars = new Memoizer<String, List<File>>() {
    @Override
    public List<File> compute(String key) {
      return Lists.newArrayList();
    }
  };

  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      throw new IllegalStateException("please set uberDir ");
    }
    File uberDir = new File(args[0]);
    if (!uberDir.exists()) {
      throw new IllegalStateException("uberDir is not exist:" + uberDir.getAbsolutePath());
    }
    File subModuleLibDir = null;

    File webStartDir = new File(uberDir, "web-start/lib");
    Set<String> existJarFiles = Sets.newHashSet(webStartDir.list());
    if (existJarFiles.size() < 1) {
      throw new IllegalStateException("webStartDir:" + webStartDir.getAbsolutePath() + " has any jar file");
    }

    // 将web-start中已有jar在子工程中去掉
    for (SubProj sbDir : subDirs) {
      subModuleLibDir = new File(uberDir, sbDir.getName() + "/lib");
      if (!subModuleLibDir.exists()) {
        throw new IllegalStateException("sub lib dir:" + subModuleLibDir.getAbsolutePath() + " is not exist");
      }
      for (String jarFileName : subModuleLibDir.list()) {
        if (existJarFiles.contains(jarFileName)) {
          FileUtils.deleteQuietly(new File(subModuleLibDir, jarFileName));
        }
      }
    }

    for (SubProj sbDir : subDirs) {
      subModuleLibDir = new File(uberDir, sbDir.getName() + "/lib");
      if (!subModuleLibDir.exists()) {
        throw new IllegalStateException("sub lib dir:" + subModuleLibDir.getAbsolutePath() + " is not exist");
      }
      for (String jarFileName : subModuleLibDir.list()) {
        if (sbDir.isMatch(jarFileName)) {
          jars.get(jarFileName).add(new File(subModuleLibDir, jarFileName));
        }
      }
    }


    boolean first = true;
    for (Map.Entry<String, List<File>> subModuleJar : jars.getEntries()) {
      System.out.println("process file:" + subModuleJar.getKey());

      for (File f : subModuleJar.getValue()) {
        if (first) {
          FileUtils.moveFile(f, new File(webStartDir, subModuleJar.getKey()));
          first = false;
        } else {
          FileUtils.deleteQuietly(f);
        }
      }
    }
  }


//  private static void forceDeleteOnExit(File f) {
//    try {
//      FileUtils.forceDeleteOnExit(f);
//    } catch (IOException e) {
//      throw new IllegalStateException("path:" + f.getAbsolutePath(), e);
//    }
//  }

  private static class SubProj extends Option {
    // 需要保留的jar包
    private final List<Pattern> retainJars = Lists.newArrayList();

    public SubProj(String name, String value, String... retainJar) {
      super(name, value);
      retainJars.add(Pattern.compile(value));
      for (String jar : retainJar) {
        retainJars.add(Pattern.compile(jar));
      }
    }

    public boolean isMatch(String jarFileName) {
      for (Pattern p : retainJars) {
        if (p.matcher(jarFileName).matches()) {
          return true;
        }
      }
      return false;
    }
  }

}