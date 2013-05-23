package com.salsalabs;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.DirectoryScanner;

@Mojo(name = "tropo", defaultPhase = LifecyclePhase.GENERATE_SOURCES, requiresDependencyResolution = ResolutionScope.COMPILE)
public class MyMojo extends AbstractMojo {
  private static final String SETUPTOOLS_EGG = "setuptools-0.6c11-py2.5.egg";

  private Artifact jythonArtifact;

  /**
   * Caching directory to download and build python packages, as well as
   * extracted jython dir
   * 
   */
  @Parameter(defaultValue = "target/troposphere-build-tmp", property = "tempDirectory")
  private File temporaryBuildDirectory;

  /**
   * The directory where the JavaCC grammar files (<code>*.jj</code>) are
   * located.
   * 
   */
  @Parameter(defaultValue = "${basedir}/src/main/troposphere", property = "sourceDirectory")
  private File sourceDirectory;

  /**
   * The directory where the parser files generated by JavaCC will be stored.
   * The directory will be registered as a
   * compile source root of the project such that the generated files will
   * participate in later build phases like
   * compiling and packaging.
   * 
   */
  @Parameter(defaultValue = "${project.build.directory}/generated-sources/troposphere", property = "outputDirectory")
  private File outputDirectory;

  /**
   * The granularity in milliseconds of the last modification date for testing
   * whether a source needs recompilation.
   * 
   */
  @Parameter(property = "lastModGranularityMs", defaultValue = "0")
  private int staleMillis;

  /**
   * A set of Ant-like inclusion patterns used to select files from the source
   * directory for processing. By default,
   * the patterns <code>**&#47;*.jj</code> and <code>**&#47;*.JJ</code> are used
   * to select grammar files.
   * 
   */
  @Parameter
  private String[] includes;

  /**
   * A set of Ant-like exclusion patterns used to prevent certain files from
   * being processed. By default, this set is
   * empty such that no files are excluded.
   * 
   */
  @Parameter
  private String[] excludes;

  @Component
  private MavenProject project;

  /**
   * The setuptools jar resource
   */
  private URL setuptoolsResource;

  /**
   * The setuptools jar, once copied from the resource
   */
  private File setuptoolsJar;

  /**
   * Lib/site-packages
   */
  private File sitepackagesdir;

  /**
   * Where packages are downloaded and built
   */
  private File packageDownloadCacheDir;

  /**
   * Lib/
   */
  private File libdir;

  /**
   * Libraries needed to include.
   * 
   * @parameter
   * @optional
   */
  @Parameter(defaultValue = "boto,troposphere")
  private List<String> libraries;

  /**
   * Should we override files during extraction if they already exist?
   * 
   * if true: will never work on tainted files; if false: will be faster.
   */
  private static final boolean OVERRIDE = false;

  protected String[] getIncludes() {
    if (this.includes != null) {
      return this.includes;
    }
    else {
      return new String[] { "**/*.tr", "**/*.TR" };
    }
  }

  protected String[] getExcludes() {
    return this.excludes;
  }

  protected File getOutputDirectory() {
    return this.outputDirectory;
  }

  protected int getStaleMillis() {
    return this.staleMillis;
  }

  protected File getSourceDirectory() {
    return this.sourceDirectory;
  }

  public void execute() throws MojoExecutionException {
    File sourceDirectory = getSourceDirectory();
    getLog().debug("source=" + sourceDirectory + " target=" + getOutputDirectory());
    if (!(sourceDirectory != null && sourceDirectory.exists())) {
      getLog().info("Request to add '" + sourceDirectory + "' folder. Not added since it does not exist.");
      return;
    }

    File f = outputDirectory;
    if (!f.exists()) {
      f.mkdirs();
    }
    setupVariables();

    extractJarToDirectory(jythonArtifact.getFile(), temporaryBuildDirectory);

    // now what? we have the jython content, now we need
    // easy_install
    getLog().info("installing easy_install ...");
    try {
      FileUtils.copyInputStreamToFile(setuptoolsResource.openStream(), setuptoolsJar);
    }
    catch (IOException e) {
      throw new MojoExecutionException("copying setuptools failed", e);
    }
    extractJarToDirectory(setuptoolsJar, new File(sitepackagesdir, SETUPTOOLS_EGG));
    try {
      IOUtils.write("./" + SETUPTOOLS_EGG + "\n", new FileOutputStream(new File(sitepackagesdir, "setuptools.pth")));
    }
    catch (IOException e) {
      throw new MojoExecutionException("writing path entry for setuptools failed", e);
    }
    getLog().info("installing easy_install done");
    if (libraries == null) {
      getLog().info("no python libraries requested");
    }
    else {
      getLog().info("installing requested python libraries");
      // then we need to call easy_install to install the other
      // dependencies.
      runJythonScriptOnInstall(temporaryBuildDirectory, getEasyInstallArgs("Lib/site-packages/" + SETUPTOOLS_EGG + "/easy_install.py"),null);
      getLog().info("installing requested python libraries done");
    }

    processFiles();
  }

  /**
   * @throws MojoExecutionException
   * 
   */
  private void processFiles() throws MojoExecutionException {
    DirectoryScanner ds = new DirectoryScanner();
    ds.setBasedir(getSourceDirectory());
    ds.setIncludes(getIncludes());
    ds.setExcludes(getExcludes());
    ds.addDefaultExcludes();
    ds.scan();
    String[] files = ds.getIncludedFiles();
    for (String file : files) {
      getLog().info("Processing file: " + file);
      File fullFile = new File(sourceDirectory,file);
      String destFile = file;
      if (FilenameUtils.indexOfExtension(file) > -1) {
        destFile = file.substring(0, FilenameUtils.indexOfExtension(file))+".template";
      }
      runJythonScriptOnInstall(temporaryBuildDirectory, getPythonArgs(fullFile.getAbsolutePath()),new File(outputDirectory,destFile));
    }
  }

  /**
   * @param file
   * @return
   * @throws MojoExecutionException
   */
  private List<String> getPythonArgs(String file) throws MojoExecutionException {
    List<String> args = new ArrayList<String>();

    // I want to launch
    args.add("java");
    // to run the generated jython installation here
    args.add("-cp");
    args.add("." + getClassPathSeparator() + "Lib");
    // which should know about itself
    args.add("-Dpython.home=.");
    File jythonFakeExecutable = new File(temporaryBuildDirectory, "jython");
    try {
      jythonFakeExecutable.createNewFile();
    }
    catch (IOException e) {
      throw new MojoExecutionException("couldn't create file", e);
    }
    args.add("-Dpython.executable=" + jythonFakeExecutable.getName());
    args.add("org.python.util.jython");
    // and it should run easy_install
    args.add(file);
    // with some arguments
    // args.add("--optimize");
    // args.add("--install-dir");
    // args.add(outputDirectory.getAbsolutePath());
    // and cache here
    args.add("--build-directory");
    args.add(packageDownloadCacheDir.getAbsolutePath());
    // and install these libraries
    args.addAll(libraries);

    return args;
  }

  private void setupVariables() throws MojoExecutionException {
    jythonArtifact = findJythonArtifact();
    if (temporaryBuildDirectory == null) {
      temporaryBuildDirectory = new File("target/jython-plugins-tmp");
    }
    temporaryBuildDirectory.mkdirs();
    packageDownloadCacheDir = new File(temporaryBuildDirectory, "build");
    packageDownloadCacheDir.mkdir();
    libdir = new File(temporaryBuildDirectory, "Lib");
    if (!jythonArtifact.getFile().getName().endsWith(".jar")) {
      throw new MojoExecutionException("I expected " + jythonArtifact + " to provide a jar, but got " + jythonArtifact.getFile());
    }

    setuptoolsResource = getClass().getResource(SETUPTOOLS_EGG);
    if (setuptoolsResource == null)
      throw new MojoExecutionException("resource setuptools egg not found");
    setuptoolsJar = new File(packageDownloadCacheDir, SETUPTOOLS_EGG);
    sitepackagesdir = new File(libdir, "site-packages");
  }

  /**
   * @return
   * @throws MojoExecutionException
   */
  private Artifact findJythonArtifact() throws MojoExecutionException {
    for (Artifact i : project.getArtifacts()) {
      if (i.getArtifactId().equals("jython-standalone") && i.getGroupId().equals("org.python")) {
        return i;
      }
    }
    throw new MojoExecutionException("org.python.jython-standalone dependency not found. " + "\n" + "Add a dependency to jython-standalone to your project: \n" + " <dependency>\n" + "   <groupId>org.python</groupId>\n" + "   <artifactId>jython-standalone</artifactId>\n" + "   <version>2.5.2</version>\n" + " </dependency>" + "\n");
  }

  public Collection<File> extractJarToDirectory(File jar, File outputDirectory) throws MojoExecutionException {
    getLog().info("extracting " + jar);
    JarFile ja = openJarFile(jar);
    Enumeration<JarEntry> en = ja.entries();
    Collection<File> files = extractAllFiles(outputDirectory, ja, en);
    closeFile(ja);
    return files;
  }

  private JarFile openJarFile(File jar) throws MojoExecutionException {
    try {
      return new JarFile(jar);
    }
    catch (IOException e) {
      throw new MojoExecutionException("opening jython artifact jar failed", e);
    }
  }

  private void closeFile(ZipFile ja) throws MojoExecutionException {
    try {
      ja.close();
    }
    catch (IOException e) {
      throw new MojoExecutionException("closing jython artifact jar failed", e);
    }
  }

  private Collection<File> extractAllFiles(File outputDirectory, ZipFile ja, Enumeration<JarEntry> en) throws MojoExecutionException {
    List<File> files = new ArrayList<File>();
    while (en.hasMoreElements()) {
      JarEntry el = en.nextElement();
      // getLog().info(" > " + el);
      if (!el.isDirectory()) {
        File destFile = new File(outputDirectory, el.getName());
        // destFile = new File(outputDirectory, destFile.getName());
        if (OVERRIDE || !destFile.exists()) {
          destFile.getParentFile().mkdirs();
          try {
            FileOutputStream fo = new FileOutputStream(destFile);
            IOUtils.copy(ja.getInputStream(el), fo);
            fo.close();
          }
          catch (IOException e) {
            throw new MojoExecutionException("extracting " + el.getName() + " from jython artifact jar failed", e);
          }
        }
        files.add(destFile);
      }
    }
    return files;
  }

  private List<String> getEasyInstallArgs(String easy_install_script) throws MojoExecutionException {
    List<String> args = new ArrayList<String>();

    // I want to launch
    args.add("java");
    // to run the generated jython installation here
    args.add("-cp");
    args.add("." + getClassPathSeparator() + "Lib");
    // which should know about itself
    args.add("-Dpython.home=.");
    File jythonFakeExecutable = new File(temporaryBuildDirectory, "jython");
    try {
      jythonFakeExecutable.createNewFile();
    }
    catch (IOException e) {
      throw new MojoExecutionException("couldn't create file", e);
    }
    args.add("-Dpython.executable=" + jythonFakeExecutable.getName());
    args.add("org.python.util.jython");
    // and it should run easy_install
    args.add(easy_install_script);
    // with some arguments
    // args.add("--optimize");
    // args.add("--install-dir");
    // args.add(outputDirectory.getAbsolutePath());
    // and cache here
    args.add("--build-directory");
    args.add(packageDownloadCacheDir.getAbsolutePath());
    // and install these libraries
    args.addAll(libraries);

    return args;
  }

  private String getClassPathSeparator() {
    if (File.separatorChar == '\\')
      return ";";
    else
      return ":";
  }

  public void runJythonScriptOnInstall(File outputDirectory, List<String> args, File outputFile) throws MojoExecutionException {
    getLog().info("running " + args + " in " + outputDirectory);
    ProcessBuilder pb = new ProcessBuilder(args);
    pb.directory(outputDirectory);
    final Process p;
    try {
      p = pb.start();
    }
    catch (IOException e) {
      throw new MojoExecutionException("Executing jython failed. tried to run: " + pb.command(), e);
    }
    if (outputFile == null) {
      copyIO(p.getInputStream(), System.out);
    }
    else
    {
      try {
        copyIO(p.getInputStream(),new FileOutputStream(outputFile));
      }
      catch (FileNotFoundException e) {
          throw new MojoExecutionException("Failed to copy output to : " + outputFile.getAbsolutePath(),e);
      }
    }
    copyIO(p.getErrorStream(), System.err);
    copyIO(System.in, p.getOutputStream());
    try {
      if (p.waitFor() != 0) {
        throw new MojoExecutionException("Jython failed with return code: " + p.exitValue());
      }
    }
    catch (InterruptedException e) {
      throw new MojoExecutionException("Python tests were interrupted", e);
    }

  }

  private void copyIO(final InputStream input, final OutputStream output) {
    new Thread(new Runnable() {

      public void run() {
        try {
          IOUtils.copy(input, output);
        }
        catch (IOException e) {
          getLog().error(e);
        }
      }
    }).start();

  }
}
