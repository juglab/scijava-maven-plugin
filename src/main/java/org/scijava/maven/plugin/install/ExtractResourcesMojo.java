/*
 * #%L
 * A plugin for managing SciJava-based projects.
 * %%
 * Copyright (C) 2014 - 2018 Board of Regents of the University of
 * Wisconsin-Madison, Broad Institute of MIT and Harvard, Max Planck
 * Institute of Molecular Cell Biology and Genetics, and KNIME GmbH.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

package org.scijava.maven.plugin.install;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.artifact.filter.resolve.ScopeFilter;
import org.apache.maven.shared.artifact.filter.resolve.TransformableFilter;
import org.apache.maven.shared.artifact.resolve.ArtifactResult;
import org.apache.maven.shared.dependencies.DefaultDependableCoordinate;
import org.apache.maven.shared.dependencies.resolve.DependencyResolver;
import org.apache.maven.shared.dependencies.resolve.DependencyResolverException;

import java.io.File;
import java.io.IOException;

/**
 * @author Johannes Schindelin
 * @author Stefan Helfrich
 * @author Deborah Schmidt
 */
@Mojo(name = "extract-resources", requiresProject = true, requiresOnline = true)
public class ExtractResourcesMojo extends AbstractCopyJarsMojo {

	/**
	 * Project
	 */
	@Parameter(defaultValue = "${project}", required=true, readonly = true)
	private MavenProject project;

	/**
	 * The dependency resolver to.
	 */
	@Component
	private DependencyResolver dependencyResolver;

	private DefaultDependableCoordinate coordinate = new DefaultDependableCoordinate();

	private File appDir;

	@Override
	public void execute() throws MojoExecutionException {

		if (appDirectory == null) {
			if (hasIJ1Dependency(project)) getLog().info(
				"Property '" + appDirectoryProperty + "' unset; Skipping extract-resources");
			return;
		}
		final String interpolated = interpolate(appDirectory, project, session);
		appDir = new File(interpolated);

		if (appSubdirectory == null) {
			getLog().info("No property name for the " + appSubdirectoryProperty +
				" directory location was specified; Installing in default location");
		}

		if (!appDir.isDirectory()) {
			getLog().warn(
				"'" + appDirectory + "'" +
					(interpolated.equals(appDirectory) ? "" : " (" + appDirectory + ")") +
					" is not an SciJava application directory; Skipping extract-resources");
			return;
		}

		// Initialize coordinate for resolving
		coordinate.setGroupId(project.getGroupId());
		coordinate.setArtifactId(project.getArtifactId());
		coordinate.setVersion(project.getVersion());
		coordinate.setType(project.getPackaging());

		try {
			TransformableFilter scopeFilter = ScopeFilter.excluding("system", "provided", "test");

			ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
			buildingRequest.setProject( project );

			Iterable<ArtifactResult> resolveDependencies = dependencyResolver
					.resolveDependencies(buildingRequest, coordinate, scopeFilter);
			for (ArtifactResult result : resolveDependencies) {
				try {
					if (project.getArtifact().equals(result.getArtifact())) {
						installArtifact(result.getArtifact(), appDir, appSubdirectory, false,
								deleteOtherVersionsPolicy);
						continue;
					}
					// Resolution of the subdirectory for dependencies is handled in installArtifact
					if (!ignoreDependencies) {
						installResource(result.getArtifact());
					}
				}
				catch (IOException e) {
					throw new MojoExecutionException("Couldn't download artifact " +
							result.getArtifact() + ": " + e.getMessage(), e);
				}
			}
		}
		catch (DependencyResolverException e) {
			throw new MojoExecutionException(
					"Couldn't resolve dependencies for artifact: " + e.getMessage(), e);
		}
	}

	private void installResource(Artifact resource) throws IOException {
		File resourceFile = resource.getFile();
		if(resourceFile.getName().endsWith(".jar")) {
			java.util.jar.JarFile jar = new java.util.jar.JarFile(resourceFile);
			java.util.Enumeration enumEntries = jar.entries();
			while (enumEntries.hasMoreElements()) {
				java.util.jar.JarEntry file = (java.util.jar.JarEntry) enumEntries.nextElement();
				if(file.getName().startsWith("Fiji.app/")) {
					java.io.File f = new java.io.File(appDir + java.io.File.separator + file.getName().substring(("Fiji.app/").length()));
					if (file.isDirectory()) {
						f.mkdir();
						continue;
					}
					java.io.InputStream is = jar.getInputStream(file);
					java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
					while (is.available() > 0) {
						fos.write(is.read());
					}
					System.out.println("Extracted " + f.getAbsolutePath() + ".");
					fos.close();
					is.close();
				}
			}
			jar.close();
		}
	}
}
