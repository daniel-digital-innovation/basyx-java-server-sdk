/*******************************************************************************
 * Copyright (C) 2023 the Eclipse BaSyx Authors
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 * SPDX-License-Identifier: MIT
 ******************************************************************************/

package org.eclipse.digitaltwin.basyx.aasenvironment.preconfiguration;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.commons.io.FilenameUtils;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.eclipse.digitaltwin.aas4j.v3.dataformat.DeserializationException;
import org.eclipse.digitaltwin.aas4j.v3.dataformat.aasx.AASXDeserializer;
import org.eclipse.digitaltwin.aas4j.v3.dataformat.aasx.InMemoryFile;
import org.eclipse.digitaltwin.aas4j.v3.dataformat.json.JsonDeserializer;
import org.eclipse.digitaltwin.aas4j.v3.dataformat.xml.XmlDeserializer;
import org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShell;
import org.eclipse.digitaltwin.aas4j.v3.model.ConceptDescription;
import org.eclipse.digitaltwin.aas4j.v3.model.Environment;
import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.basyx.aasenvironment.FileElementPathCollector;
import org.eclipse.digitaltwin.basyx.aasenvironment.IdShortPathBuilder;
import org.eclipse.digitaltwin.basyx.aasrepository.AasRepository;
import org.eclipse.digitaltwin.basyx.conceptdescriptionrepository.ConceptDescriptionRepository;
import org.eclipse.digitaltwin.basyx.submodelrepository.SubmodelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.integration.file.RecursiveDirectoryScanner;
import org.springframework.stereotype.Component;

/**
 * Loader for AAS environment pre-configuration
 *
 * @author fried, mateusmolina, despen, witt, jungjan, danish
 *
 */
@Component
public class AasEnvironmentPreconfigurationLoader {
	
	private Logger logger = LoggerFactory.getLogger(AasEnvironmentPreconfigurationLoader.class);

	@Value("${basyx.environment:#{null}}")
	private List<String> pathsToLoad;

	private ResourceLoader resourceLoader;
	
	private List<InMemoryFile> relatedFiles;

	@Autowired
	public AasEnvironmentPreconfigurationLoader(ResourceLoader resourceLoader, List<String> pathsToLoad) {
		this.resourceLoader = resourceLoader;
		this.pathsToLoad = pathsToLoad;
	}

	public boolean shouldLoadPreconfiguredEnvironment() {
		return pathsToLoad != null;
	}

	public void loadPreconfiguredEnvironment(AasRepository aasRepository, SubmodelRepository submodelRepository, ConceptDescriptionRepository conceptDescriptionRepository)
			throws IOException, DeserializationException, InvalidFormatException {
		List<File> files = resolveFiles(pathsToLoad);
		for (File file : files) {
			Environment environment = getEnvironmentFromFile(file);
			loadEnvironmentFromFile(aasRepository, submodelRepository, conceptDescriptionRepository, environment);
		}
	}

	private List<File> resolveFiles(List<String> paths) throws IOException {
		ArrayList<File> files = new ArrayList<>();

		for (String path : paths) {
			resolvePathAndAddFilesToList(files, path);
		}
		return files;
	}

	private void resolvePathAndAddFilesToList(ArrayList<File> files, String path) throws IOException {
		if (!getFile(path).isFile()) {
			List<File> filesFromDir = extractFilesToLoadFromEnvironmentDirectory(path);
			files.addAll(filesFromDir);
		} else {
			files.add(getFile(path));
		}
	}

	private File getFile(String filePath) throws IOException {
		return resourceLoader.getResource(filePath)
				.getFile();
	}

	private void loadEnvironmentFromFile(AasRepository aasRepository, SubmodelRepository submodelRepository, ConceptDescriptionRepository conceptDescriptionRepository, Environment environment) {
		if (isEnvironmentLoaded(environment)) {
			createShellsOnRepositoryFromEnvironment(aasRepository, environment);
			createSubmodelsOnRepositoryFromEnvironment(submodelRepository, environment);
			createConceptDescriptionsOnRepositoryFromEnvironment(conceptDescriptionRepository, environment);
		}
	}

	private List<File> extractFilesToLoadFromEnvironmentDirectory(String directoryToLoad) throws IllegalArgumentException, IOException {
		File rootDirectory = getFile(directoryToLoad);
		RecursiveDirectoryScanner directoryScanner = new RecursiveDirectoryScanner();

		List<File> potentialEnvironments = directoryScanner.listFiles(rootDirectory);
		return potentialEnvironments.stream()
				.filter(file -> isAasxFile(file.getPath()) || isJsonFile(file.getPath()) || isXmlFile(file.getPath()))
				.collect(Collectors.toList());
	}

	private void createConceptDescriptionsOnRepositoryFromEnvironment(ConceptDescriptionRepository conceptDescriptionRepository, Environment environment) {
		for (ConceptDescription conceptDescription : environment.getConceptDescriptions()) {
			conceptDescriptionRepository.createConceptDescription(conceptDescription);
		}
	}

	private void createSubmodelsOnRepositoryFromEnvironment(SubmodelRepository submodelRepository, Environment environment) {
		List<Submodel> submodels = environment.getSubmodels();
		
		submodels.stream().forEach(submodelRepository::createSubmodel);
		
		if (relatedFiles == null || relatedFiles.isEmpty())
			return;
		
		for (Submodel submodel : submodels) {
			List<List<SubmodelElement>> idShortElementPathsOfAllFileSMEs = new FileElementPathCollector(submodel).collect();
			
			idShortElementPathsOfAllFileSMEs.stream().forEach(fileSMEIdShortPath -> setFileToFileElement(submodel.getId(), fileSMEIdShortPath, submodelRepository));
		}
	}

	private void setFileToFileElement(String submodelId, List<SubmodelElement> fileSMEIdShortPathElements, SubmodelRepository submodelRepository) {
		String fileSMEIdShortPath = new IdShortPathBuilder(new ArrayList<>(fileSMEIdShortPathElements)).build();
		
		org.eclipse.digitaltwin.aas4j.v3.model.File fileSME = (org.eclipse.digitaltwin.aas4j.v3.model.File) submodelRepository.getSubmodelElement(submodelId, fileSMEIdShortPath);
		
		InMemoryFile inMemoryFile = getAssociatedInMemoryFile(relatedFiles, fileSME.getValue());
		
		if (inMemoryFile == null) {
			logger.info("Unable to set file to the SubmodelElement File with IdShortPath '{}' because it does not exist in the AASX file.", fileSMEIdShortPath);
			
			return;
		}
		
		submodelRepository.setFileValue(submodelId, fileSMEIdShortPath, getFileName(inMemoryFile.getPath()), new ByteArrayInputStream(inMemoryFile.getFileContent()));
	}

	private String getFileName(String path) {
		return FilenameUtils.getName(path);
	}

	private InMemoryFile getAssociatedInMemoryFile(List<InMemoryFile> relatedFiles, String value) {
		
		Optional<InMemoryFile> inMemoryFile = relatedFiles.stream().filter(file -> file.getPath().equals(value)).findAny();
		
		if (inMemoryFile.isEmpty())
			return null;
		
		return inMemoryFile.get();
	}

	private void createShellsOnRepositoryFromEnvironment(AasRepository aasRepository, Environment environment) {
		for (AssetAdministrationShell aas : environment.getAssetAdministrationShells()) {
			aasRepository.createAas(aas);
		}
	}

	private Environment getEnvironmentFromFile(File file) throws DeserializationException, InvalidFormatException, IOException {
		Environment environment = null;
		if (isJsonFile(file.getPath())) {
			JsonDeserializer deserializer = new JsonDeserializer();
			environment = deserializer.read(new FileInputStream(file));
		} else if (isXmlFile(file.getPath())) {
			XmlDeserializer deserializer = new XmlDeserializer();
			environment = deserializer.read(new FileInputStream(file));
		} else if (isAasxFile(file.getPath())) {
			AASXDeserializer deserializer = new AASXDeserializer(new FileInputStream(file));
			
			relatedFiles = deserializer.getRelatedFiles();
			
			environment = deserializer.read();
		}
		return environment;
	}

	private static boolean isJsonFile(String filePath) {
		return filePath.endsWith(".json");
	}

	private static boolean isXmlFile(String filePath) {
		return filePath.endsWith(".xml");
	}

	private static boolean isAasxFile(String filePath) {
		return filePath.endsWith(".aasx");
	}

	private boolean isEnvironmentLoaded(Environment environment) {
		return environment != null;
	}
}
