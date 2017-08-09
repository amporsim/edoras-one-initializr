/*
 * Copyright 2012-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.edorasware.one.initializr.web.controller;

import com.edorasware.one.initializr.generator.BasicProjectRequest;
import com.edorasware.one.initializr.generator.ProjectGenerator;
import com.edorasware.one.initializr.generator.ProjectRequest;
import com.edorasware.one.initializr.metadata.DependencyMetadata;
import com.edorasware.one.initializr.metadata.DependencyMetadataProvider;
import com.edorasware.one.initializr.metadata.InitializrMetadata;
import com.edorasware.one.initializr.metadata.InitializrMetadataProvider;
import com.edorasware.one.initializr.util.Version;
import com.edorasware.one.initializr.web.mapper.*;
import com.samskivert.mustache.Mustache;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.taskdefs.Tar;
import org.apache.tools.ant.taskdefs.Zip;
import org.apache.tools.ant.types.TarFileSet;
import org.apache.tools.ant.types.ZipFileSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.DigestUtils;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.resource.ResourceUrlProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The main initializr controller provides access to the configured metadata and serves as
 * a central endpoint to generate projects or build files.
 *
 * @author Dave Syer
 * @author Stephane Nicoll
 */
@Controller
public class MainController extends AbstractInitializrController {

	private static final Logger log = LoggerFactory.getLogger(MainController.class);

	public static final MediaType HAL_JSON_CONTENT_TYPE = MediaType
			.parseMediaType("application/hal+json");

	private final ProjectGenerator projectGenerator;
	private final DependencyMetadataProvider dependencyMetadataProvider;

	public MainController(InitializrMetadataProvider metadataProvider,
                          ResourceUrlProvider resourceUrlProvider,
                          ProjectGenerator projectGenerator,
                          DependencyMetadataProvider dependencyMetadataProvider) {
		super(metadataProvider, resourceUrlProvider);
		this.projectGenerator = projectGenerator;
		this.dependencyMetadataProvider = dependencyMetadataProvider;
	}

	@ModelAttribute
	public BasicProjectRequest projectRequest(
			@RequestHeader Map<String, String> headers) {
		ProjectRequest request = new ProjectRequest();
		request.getParameters().putAll(headers);
		request.initialize(metadataProvider.get());
		return request;
	}

	@RequestMapping(value = "/metadata/config", produces = "application/json")
	@ResponseBody
	public InitializrMetadata config() {
		return metadataProvider.get();
	}

	@RequestMapping(value = "/metadata/client")
	public String client() {
		return "redirect:/";
	}

	@RequestMapping(value = "/", produces = "application/hal+json")
	public ResponseEntity<String> serviceCapabilitiesHal() {
		return serviceCapabilitiesFor(InitializrMetadataVersion.V2_1,
				HAL_JSON_CONTENT_TYPE);
	}

	@RequestMapping(value = "/", produces = { "application/vnd.initializr.v2.1+json",
			"application/json" })
	public ResponseEntity<String> serviceCapabilitiesV21() {
		return serviceCapabilitiesFor(InitializrMetadataVersion.V2_1);
	}

	@RequestMapping(value = "/", produces = "application/vnd.initializr.v2+json")
	public ResponseEntity<String> serviceCapabilitiesV2() {
		return serviceCapabilitiesFor(InitializrMetadataVersion.V2);
	}

	private ResponseEntity<String> serviceCapabilitiesFor(
			InitializrMetadataVersion version) {
		return serviceCapabilitiesFor(version, version.getMediaType());
	}

	private ResponseEntity<String> serviceCapabilitiesFor(
			InitializrMetadataVersion version, MediaType contentType) {
		String appUrl = generateAppUrl();
		String content = getJsonMapper(version).write(metadataProvider.get(), appUrl);
		return ResponseEntity.ok().contentType(contentType).eTag(createUniqueId(content))
				.cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS)).body(content);
	}

	private static InitializrMetadataJsonMapper getJsonMapper(
			InitializrMetadataVersion version) {
		switch (version) {
			case V2:
				return new InitializrMetadataV2JsonMapper();
			default:
				return new InitializrMetadataV21JsonMapper();
		}
	}

	@RequestMapping(value = "/dependencies", produces = {
			"application/vnd.initializr.v2.1+json", "application/json" })
	public ResponseEntity<String> dependenciesV21(
			@RequestParam(required = false) String edorasoneVersion) {
		return dependenciesFor(InitializrMetadataVersion.V2_1, edorasoneVersion);
	}

	private ResponseEntity<String> dependenciesFor(InitializrMetadataVersion version,
			String edorasoneVersion) {
		InitializrMetadata metadata = metadataProvider.get();
		Version v = edorasoneVersion != null ? Version.parse(edorasoneVersion)
				: Version.parse(metadata.getEdorasoneVersions().getDefault().getId());
		DependencyMetadata dependencyMetadata = dependencyMetadataProvider.get(metadata,
				v);
		String content = new DependencyMetadataV21JsonMapper().write(dependencyMetadata);
		return ResponseEntity.ok().contentType(version.getMediaType())
				.eTag(createUniqueId(content))
				.cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS)).body(content);
	}

	@ModelAttribute("linkTo")
	public Mustache.Lambda linkTo() {
		return (frag, out) -> out.write(this.getLinkTo().apply(frag.execute()));
	}

	@RequestMapping(value = "/", produces = "text/html")
	public String home(Map<String, Object> model) {
		renderHome(model);
		return "home";
	}

	@RequestMapping("/pom")
	@ResponseBody
	public ResponseEntity<byte[]> pom(BasicProjectRequest request) {
		request.setType("maven-build");
		byte[] mavenPom = projectGenerator.generateMavenPom((ProjectRequest) request);
		return createResponseEntity(mavenPom, "application/octet-stream", "pom.xml");
	}

	@RequestMapping("/build")
	@ResponseBody
	public ResponseEntity<byte[]> gradle(BasicProjectRequest request) {
		request.setType("gradle-build");
		byte[] gradleBuild = projectGenerator
				.generateGradleBuild((ProjectRequest) request);
		return createResponseEntity(gradleBuild, "application/octet-stream",
				"build.gradle");
	}

	@RequestMapping("/starter.zip")
	@ResponseBody
	public ResponseEntity<byte[]> springZip(BasicProjectRequest basicRequest)
			throws IOException {
		ProjectRequest request = (ProjectRequest) basicRequest;
		File dir = projectGenerator.generateProjectStructure(request);

		File download = projectGenerator.createDistributionFile(dir, ".zip");

		dir.setExecutable(true);
		Zip zip = new Zip();
		zip.setProject(new Project());
		zip.setDefaultexcludes(false);
		ZipFileSet set = new ZipFileSet();
		set.setDir(dir);
		set.setFileMode("755");
		set.setDefaultexcludes(false);
		zip.addFileset(set);
		zip.setDestFile(download.getCanonicalFile());
		zip.execute();
		return upload(download, dir, generateFileName(request, "zip"), "application/zip");
	}

	@RequestMapping(value = "/starter.tgz", produces = "application/x-compress")
	@ResponseBody
	public ResponseEntity<byte[]> springTgz(BasicProjectRequest basicRequest)
			throws IOException {
		ProjectRequest request = (ProjectRequest) basicRequest;
		File dir = projectGenerator.generateProjectStructure(request);

		File download = projectGenerator.createDistributionFile(dir, ".tar.gz");

		dir.setExecutable(true);
		Tar zip = new Tar();
		zip.setProject(new Project());
		zip.setDefaultexcludes(false);
		TarFileSet set = zip.createTarFileSet();
		set.setDir(dir);
		set.setFileMode("755");
		set.setDefaultexcludes(false);
		zip.setDestFile(download.getCanonicalFile());
		Tar.TarCompressionMethod method = new Tar.TarCompressionMethod();
		method.setValue("gzip");
		zip.setCompression(method );
		zip.execute();
		return upload(download, dir, generateFileName(request, "tar.gz"),
				"application/x-compress");
	}

	private static String generateFileName(ProjectRequest request, String extension) {
		String tmp = request.getArtifactId().replaceAll(" ", "_");
		try {
			return URLEncoder.encode(tmp, "UTF-8") + "." + extension;
		}
		catch (UnsupportedEncodingException e) {
			throw new IllegalStateException("Cannot encode URL", e);
		}
	}

	private ResponseEntity<byte[]> upload(File download, File dir, String fileName,
			String contentType) throws IOException {
		byte[] bytes = StreamUtils.copyToByteArray(new FileInputStream(download));
		log.info("Uploading: {} ({} bytes)", download, bytes.length);
		ResponseEntity<byte[]> result = createResponseEntity(bytes, contentType,
				fileName);
		projectGenerator.cleanTempFiles(dir);
		return result;
	}

	private ResponseEntity<byte[]> createResponseEntity(byte[] content,
			String contentType, String fileName) {
		String contentDispositionValue = "attachment; filename=\"" + fileName + "\"";
		return ResponseEntity.ok().header("Content-Type", contentType)
				.header("Content-Disposition", contentDispositionValue).body(content);
	}

	private String createUniqueId(String content) {
		StringBuilder builder = new StringBuilder();
		DigestUtils.appendMd5DigestAsHex(content.getBytes(StandardCharsets.UTF_8),
				builder);
		return builder.toString();
	}

}


