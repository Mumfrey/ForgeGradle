/*
 * A Gradle plugin for the creation of Minecraft mods and MinecraftForge plugins.
 * Copyright (C) 2013 Minecraft Forge
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */
package net.minecraftforge.gradle.user.liteloader;

import static net.minecraftforge.gradle.common.Constants.*;
import static net.minecraftforge.gradle.user.UserConstants.*;

import com.google.common.base.Throwables;
import net.minecraftforge.gradle.tasks.EtagDownloadTask;
import net.minecraftforge.gradle.user.UserVanillaBasePlugin;
import net.minecraftforge.gradle.util.delayed.DelayedFile;
import net.minecraftforge.gradle.util.json.JsonFactory;
import net.minecraftforge.gradle.util.json.LiteLoaderJson;
import net.minecraftforge.gradle.util.json.LiteLoaderJson.Artifact;
import net.minecraftforge.gradle.util.json.LiteLoaderJson.RepoObject;
import net.minecraftforge.gradle.util.json.LiteLoaderJson.VersionObject;
import org.gradle.api.Action;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.jvm.tasks.Jar;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class LiteloaderPlugin extends UserVanillaBasePlugin<LiteloaderExtension>
{
    public static final String CONFIG_LL_DEOBF_COMPILE = "liteloaderDeobfCompile";
    public static final String CONFIG_LL_DC_RESOLVED = "liteloaderResolvedDeobfCompile";

    public static final String MAVEN_REPO_NAME = "liteloaderRepo";

    public static final String MOD_EXTENSION = "litemod";
    
    public static final String VERSION_JSON_URL = "http://dl.liteloader.com/versions/versions.json";
    public static final String VERSION_JSON_FILENAME = "versions.json";
    public static final String VERSION_JSON_FILE = REPLACE_CACHE_DIR + "/com/mumfrey/liteloader/" + VERSION_JSON_FILENAME;
    
    private LiteLoaderJson json;

    private RepoObject repo;

    private Artifact artifact;

    @Override
    protected void applyVanillaUserPlugin()
    {
        final ConfigurationContainer configs = project.getConfigurations();
        final TaskContainer tasks = project.getTasks();
        
        configs.maybeCreate(CONFIG_LL_DEOBF_COMPILE);
        configs.maybeCreate(CONFIG_LL_DC_RESOLVED);

        configs.getByName(CONFIG_DC_RESOLVED).extendsFrom(configs.getByName(CONFIG_LL_DC_RESOLVED));
        
        final DelayedFile versionJson = delayedFile(VERSION_JSON_FILE);
        final DelayedFile versionJsonEtag = delayedFile(VERSION_JSON_FILE + ".etag");
        setJson(JsonFactory.loadLiteLoaderJson(getWithEtag(VERSION_JSON_URL, versionJson.call(), versionJsonEtag.call())));

        Jar jar = (Jar) tasks.getByName("jar");
        jar.setExtension(MOD_EXTENSION);
    }
    
    @Override
    protected void setupDevTimeDeobf(final Task compileDummy, final Task providedDummy)
    {
        super.setupDevTimeDeobf(compileDummy, providedDummy);
        
        // die with error if I find invalid types...
        project.afterEvaluate(new Action<Project>() {
            @Override
            public void execute(Project project)
            {
                if (project.getState().getFailure() != null)
                    return;

                propagate();
                remapDeps(project, project.getConfigurations().getByName(CONFIG_LL_DEOBF_COMPILE), CONFIG_LL_DC_RESOLVED, compileDummy);
            }
        });
    }
    
    void propagate()
    {
        project.allprojects(new Action<Project>() {
            @Override
            public void execute(Project proj)
            {
                RepoObject repo = LiteloaderPlugin.this.getRepo();
                if (repo != null)
                {
                    addMavenRepo(proj, MAVEN_REPO_NAME, repo.url);
                    
                    Artifact artifact = LiteloaderPlugin.this.getArtifact();
                    if (artifact != null)
                    {
                        proj.getDependencies().add(CONFIG_LL_DEOBF_COMPILE, artifact.getDepString(repo));
                        if (artifact.libraries != null)
                        {
                            for (Map<String, String> library : artifact.libraries)
                            {
                                String name = library.get("name");
                                if (name != null)
                                {
                                    proj.getDependencies().add(CONFIG_MC_DEPS, name);
                                }
                                
                                String url = library.get("url");
                                if (url != null)
                                {
                                    addMavenRepo(proj, url, url);
                                }
                            }
                        }
                    }
                }
            }
        });
    }

    public LiteLoaderJson getJson()
    {
        return this.json;
    }
    
    public void setJson(LiteLoaderJson json)
    {
        this.json = json;
    }
    
    public void applyAndCheckJson()
    {
        String mcVersion = delayedString(REPLACE_MC_VERSION).call();
        
        VersionObject version = this.json.versions.get(mcVersion);
        if (version == null)
        {
            throw new InvalidUserDataException("No ForgeGradle-compatible LiteLoader version found for Minecraft" + mcVersion);
        }
        
        this.setRepo(version.repo);
        this.setArtifact(version.latest);
    }
    
    public RepoObject getRepo()
    {
        return this.repo;
    }
    
    public void setRepo(RepoObject repo)
    {
        this.repo = repo;
    }
    
    public Artifact getArtifact()
    {
        return this.artifact;
    }
    
    public void setArtifact(Artifact artifact)
    {
        this.artifact = artifact;
    }
    
    @Override
    protected String getJarName()
    {
        return "minecraft";
    }

    @Override
    protected void createDecompTasks(String globalPattern, String localPattern)
    {
        super.makeDecompTasks(globalPattern, localPattern, delayedFile(JAR_CLIENT_FRESH), TASK_DL_CLIENT, delayedFile(MCP_PATCHES_CLIENT));
    }

    @Override
    protected boolean hasServerRun()
    {
        return false;
    }

    @Override
    protected boolean hasClientRun()
    {
        return true;
    }

    @Override
    protected Object getStartDir()
    {
        return delayedFile(REPLACE_CACHE_DIR + "/net/minecraft/" + getJarName() + "/" + REPLACE_MC_VERSION + "/start");
    }

    @Override
    protected String getClientTweaker(LiteloaderExtension ext)
    {
        return "com.mumfrey.liteloader.launch.LiteLoaderTweaker";
    }

    @Override
    protected String getClientRunClass(LiteloaderExtension ext)
    {
        return "com.mumfrey.liteloader.debug.Start";
    }

    @Override
    protected String getServerTweaker(LiteloaderExtension ext)
    {
        return "";// never run on server.. so...
    }

    @Override
    protected String getServerRunClass(LiteloaderExtension ext)
    {
        // irrelevant..
        return "";
    }

    @Override
    protected List<String> getClientJvmArgs(LiteloaderExtension ext)
    {
        return ext.getResolvedClientJvmArgs();
    }

    @Override
    protected List<String> getServerJvmArgs(LiteloaderExtension ext)
    {
        return ext.getResolvedServerJvmArgs();
    }
}
