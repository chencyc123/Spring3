/*******************************************************************************
 * Copyright (c) 2005, 2009 Spring IDE Developers
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Spring IDE Developers - initial API and implementation
 *******************************************************************************/
package org.springframework.ide.eclipse.beans.core.internal.model;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.ui.IPersistableElement;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.parsing.AliasDefinition;
import org.springframework.beans.factory.parsing.BeanComponentDefinition;
import org.springframework.beans.factory.parsing.BeanDefinitionParsingException;
import org.springframework.beans.factory.parsing.ComponentDefinition;
import org.springframework.beans.factory.parsing.CompositeComponentDefinition;
import org.springframework.beans.factory.parsing.DefaultsDefinition;
import org.springframework.beans.factory.parsing.EmptyReaderEventListener;
import org.springframework.beans.factory.parsing.ImportDefinition;
import org.springframework.beans.factory.parsing.Problem;
import org.springframework.beans.factory.parsing.ProblemReporter;
import org.springframework.beans.factory.parsing.ReaderEventListener;
import org.springframework.beans.factory.parsing.SourceExtractor;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.beans.factory.xml.DocumentDefaultsDefinition;
import org.springframework.beans.factory.xml.NamespaceHandlerResolver;
import org.springframework.beans.factory.xml.PluggableSchemaResolver;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.ide.eclipse.beans.core.BeansCorePlugin;
import org.springframework.ide.eclipse.beans.core.DefaultBeanDefinitionRegistry;
import org.springframework.ide.eclipse.beans.core.internal.model.process.BeansConfigPostProcessorFactory;
import org.springframework.ide.eclipse.beans.core.internal.parser.BeansDtdResolver;
import org.springframework.ide.eclipse.beans.core.model.IBean;
import org.springframework.ide.eclipse.beans.core.model.IBeanAlias;
import org.springframework.ide.eclipse.beans.core.model.IBeansComponent;
import org.springframework.ide.eclipse.beans.core.model.IBeansConfig;
import org.springframework.ide.eclipse.beans.core.model.IBeansConfigSet;
import org.springframework.ide.eclipse.beans.core.model.IBeansImport;
import org.springframework.ide.eclipse.beans.core.model.IBeansProject;
import org.springframework.ide.eclipse.beans.core.model.process.IBeansConfigPostProcessor;
import org.springframework.ide.eclipse.beans.core.namespaces.IModelElementProvider;
import org.springframework.ide.eclipse.beans.core.namespaces.NamespaceUtils;
import org.springframework.ide.eclipse.core.SpringCore;
import org.springframework.ide.eclipse.core.io.EclipsePathMatchingResourcePatternResolver;
import org.springframework.ide.eclipse.core.io.ExternalFile;
import org.springframework.ide.eclipse.core.io.FileResource;
import org.springframework.ide.eclipse.core.io.StorageResource;
import org.springframework.ide.eclipse.core.io.ZipEntryStorage;
import org.springframework.ide.eclipse.core.io.xml.LineNumberPreservingDOMParser;
import org.springframework.ide.eclipse.core.io.xml.XercesDocumentLoader;
import org.springframework.ide.eclipse.core.java.Introspector;
import org.springframework.ide.eclipse.core.java.JdtUtils;
import org.springframework.ide.eclipse.core.model.DefaultModelSourceLocation;
import org.springframework.ide.eclipse.core.model.ILazyInitializedModelElement;
import org.springframework.ide.eclipse.core.model.IModelElement;
import org.springframework.ide.eclipse.core.model.IModelSourceLocation;
import org.springframework.ide.eclipse.core.model.IResourceModelElement;
import org.springframework.ide.eclipse.core.model.ISourceModelElement;
import org.springframework.ide.eclipse.core.model.validation.ValidationProblem;
import org.springframework.ide.eclipse.core.model.xml.XmlSourceLocation;
import org.springframework.util.ClassUtils;
import org.w3c.dom.Node;
import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * This class defines a Spring beans configuration.
 * @author Torsten Juergeleit
 * @author Christian Dupuis
 */
public class BeansConfig extends AbstractBeansConfig implements IBeansConfig,
		ILazyInitializedModelElement {

	/** The default element provider used for non-namespaced elements */
	public static final IModelElementProvider DEFAULT_ELEMENT_PROVIDER = new DefaultModelElementProvider();

	/** The resource that is currently being processed or null if non is processed s */
	private transient IResource currentResource = null;

	/**
	 * Creates a new {@link BeansConfig}.
	 */
	public BeansConfig(IBeansProject project, String name, Type type) {
		super(project, name, type);
		init(name, project);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public IModelElement[] getElementChildren() {
		// Lazily initialization of this config
		readConfig();

		try {
			r.lock();
			List<ISourceModelElement> children = new ArrayList<ISourceModelElement>(imports);
			children.addAll(aliases.values());
			children.addAll(components);
			children.addAll(beans.values());
			Collections.sort(children, new Comparator<ISourceModelElement>() {
				public int compare(ISourceModelElement element1, ISourceModelElement element2) {
					return element1.getElementStartLine() - element2.getElementStartLine();
				}
			});
			return children.toArray(new IModelElement[children.size()]);
		}
		finally {
			r.unlock();
		}
	}

	/**
	 * {@inheritDoc}
	 */
	public boolean isInitialized() {
		return isModelPopulated;
	}

	/**
	 * Sets internal list of {@link IBean}s to <code>null</code>. Any further access to the data of
	 * this instance of {@link IBeansConfig} leads to reloading of the corresponding beans config
	 * file.
	 */
	public void reload() {
		if (file != null) {
			try {
				w.lock();
				isModelPopulated = false;
				modificationTimestamp = IResource.NULL_STAMP;
				defaults = null;
				imports = null;
				aliases = null;
				beans = null;
				isBeanClassesMapPopulated = false;
				beanClassesMap = null;
				problems = null;

				// Reset all config sets which contain this config
				for (IBeansConfigSet configSet : ((IBeansProject) getElementParent())
						.getConfigSets()) {
					if (configSet.hasConfig(getElementName())) {
						((BeansConfigSet) configSet).reset();
					}
				}
			}
			finally {
				w.unlock();
			}
		}
	}

	/**
	 * Checks the file for the given name. If the given name defines an external resource (leading
	 * '/' -> not part of the project this config belongs to) get the file from the workspace else
	 * from the project. If the name specifies an entry in an archive then the {@link #isArchived}
	 * flag is set. If the corresponding file is not available or accessible then an entry is added
	 * to the config's list of errors.
	 * @param project
	 */
	protected void init(String name, IBeansProject project) {
		IContainer container;
		String fileName;
		String fullPath;

		// At first check for a config file in a JAR
		int pos = name.indexOf(ZipEntryStorage.DELIMITER);
		if (pos != -1) {
			isArchived = true;
			fileName = name.substring(0, pos);
		}
		else {
			fileName = name;
		}

		// Now check for an external config file
		if (name.charAt(0) == '/') {
			container = ResourcesPlugin.getWorkspace().getRoot();
			fullPath = fileName;
		}
		else {
			container = (IProject) ((IResourceModelElement) getElementParent())
					.getElementResource();
			fullPath = container.getFullPath().append(fileName).toString();
		}

		// Make sure that all config resources are in sync with the file system
		IFile fileHandle = container.getFile(new Path(fileName));
		if (!fileHandle.isSynchronized(IResource.DEPTH_ONE)) {
			try {
				fileHandle.refreshLocal(IResource.DEPTH_ONE, new NullProgressMonitor());
			}
			catch (CoreException e) {
				SpringCore.log("Cannot sync state of file '" + fileHandle.getFullPath().toString()
						+ "'", e);
			}
		}

		// Try to find the configuration file in the workspace
		file = (IFile) container.findMember(fileName);

		// If the file couldn't be found in the workspace that could either mean we are dealing with
		// an external resource or the file as been deleted/moved
		if (file == null) {

			// Resolve eventual contained classpath variables
			IPath resolvedPath = JavaCore.getResolvedVariablePath(new Path(fileName));
			if (resolvedPath != null) {
				fileName = resolvedPath.toString();
			}

			// Create an external file instance
			file = new ExternalFile(new File(fileName), name.substring(pos + 1), project
					.getProject());

			// Does the external resource exist?
			if (!file.exists()) {
				modificationTimestamp = IResource.NULL_STAMP;
				String msg = "Beans config file '" + fullPath + "' not accessible";
				problems = new LinkedHashSet<ValidationProblem>();
				problems.add(new ValidationProblem(IMarker.SEVERITY_ERROR, msg, file, -1));
			}
		}
		else {
			modificationTimestamp = file.getModificationStamp();
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void readConfig() {
		if (!this.isModelPopulated) {

			long start = System.currentTimeMillis();

			// Only install Eclipse-based resource loader if enabled in project properties
			// IMPORTANT: the following block needs to stay before the w.lock()
			// as it could otherwise create a runtime deadlock
			ResourceLoader resourceLoader = null;
			if (((IBeansProject) getElementParent()).isImportsEnabled()) {
				resourceLoader = new EclipsePathMatchingResourcePatternResolver(file.getProject());
			}
			else {
				resourceLoader = new ClassResourceFilteringPatternResolver(file.getProject());
			}

			try {
				w.lock();
				if (this.isModelPopulated) {
					return;
				}
				imports = new LinkedHashSet<IBeansImport>();
				aliases = new LinkedHashMap<String, IBeanAlias>();
				components = new LinkedHashSet<IBeansComponent>();
				beans = new LinkedHashMap<String, IBean>();
				problems = new LinkedHashSet<ValidationProblem>();

				if (file != null) {
					modificationTimestamp = file.getModificationStamp();
					final Resource resource;
					if (isArchived) {
						if (file instanceof Resource) {
							resource = (Resource) file;
						}
						else {
							resource = new StorageResource(new ZipEntryStorage(file.getProject(),
									getElementName()));
						}
					}
					else {
						resource = new FileResource(file);
					}

					DefaultBeanDefinitionRegistry registry = new DefaultBeanDefinitionRegistry();
					EntityResolver resolver = new XmlCatalogDelegatingEntityResolver(
							new BeansDtdResolver(), new PluggableSchemaResolver(
									PluggableSchemaResolver.class.getClassLoader()));
					final SourceExtractor sourceExtractor = new DelegatingSourceExtractor(file
							.getProject());
					final BeansConfigReaderEventListener eventListener = new BeansConfigReaderEventListener(
							this, resource, sourceExtractor);
					final ProblemReporter problemReporter = new BeansConfigProblemReporter();
					final BeanNameGenerator beanNameGenerator = new UniqueBeanNameGenerator(this);

					final XmlBeanDefinitionReader reader = new XmlBeanDefinitionReader(registry) {
						@Override
						public int loadBeanDefinitions(EncodedResource encodedResource)
								throws BeanDefinitionStoreException {

							// Capture the current resource being processed to handle parsing
							// exceptions correctly and create the validation error on the correct
							// resource
							if (encodedResource != null
									&& encodedResource.getResource() instanceof IAdaptable) {
								currentResource = (IResource) ((IAdaptable) encodedResource
										.getResource()).getAdapter(IResource.class);

							}

							try {
								// Delegate actual processing to XmlBeanDefinitionReader
								return super.loadBeanDefinitions(encodedResource);
							}
							finally {
								// Reset currently processed resource before leaving
								currentResource = null;
							}

						}
					};

					reader.setDocumentLoader(new XercesDocumentLoader());
					reader.setResourceLoader(resourceLoader);

					reader.setEntityResolver(resolver);
					reader.setSourceExtractor(sourceExtractor);
					reader.setEventListener(eventListener);
					reader.setProblemReporter(problemReporter);
					reader.setErrorHandler(new BeansConfigErrorHandler());
					reader.setNamespaceHandlerResolver(new DelegatingNamespaceHandlerResolver(
							NamespaceHandlerResolver.class.getClassLoader(), this));
					reader.setBeanNameGenerator(beanNameGenerator);

					try {

						final Set<Throwable> throwables = new HashSet<Throwable>();

						Callable<Integer> loadBeanDefinitionOperation = new Callable<Integer>() {

							public Integer call() {

								try {
									// Load bean definitions
									int count = reader.loadBeanDefinitions(resource);

									// Finally register post processed beans and components
									eventListener.registerComponents();

									// Post process beans config if required
									postProcess(problemReporter, beanNameGenerator, resource);

									return count;
								}
								catch (Exception e) {
									// Record the exception to throw it later
									throwables.add(e);
								}
								return 0;
							}

						};

						try {
							FutureTask<Integer> task = new FutureTask<Integer>(
									loadBeanDefinitionOperation);
							BeansCorePlugin.getExecutorService().submit(task);
							int count = task.get(BeansCorePlugin.getDefault().getPreferenceStore()
									.getInt(BeansCorePlugin.TIMEOUT_CONFIG_LOADING_PREFERENCE_ID),
									TimeUnit.SECONDS);

							if (BeansModel.DEBUG) {
								System.out.println(count + " bean definitions loaded from '"
										+ resource.getFile().getAbsolutePath() + "'");
							}
							// if we recored an exception use this instead of stupid concurrent
							// exception
							if (throwables.size() > 0) {
								throw throwables.iterator().next();
							}
						}
						catch (TimeoutException e) {
							problems
									.add(new ValidationProblem(
											IMarker.SEVERITY_ERROR,
											"Loading of resource '"
													+ resource.getFile().getAbsolutePath()
													+ "' took more than "
													+ BeansCorePlugin
															.getDefault()
															.getPreferenceStore()
															.getInt(
																	BeansCorePlugin.TIMEOUT_CONFIG_LOADING_PREFERENCE_ID)
													+ "sec", file, -1));
						}
					}
					catch (Throwable e) {

						// Skip SAXParseExceptions because they're already
						// handled by the SAX ErrorHandler
						if (!(e.getCause() instanceof SAXParseException)
								&& !(e instanceof BeanDefinitionParsingException)) {
							problems.add(new ValidationProblem(IMarker.SEVERITY_ERROR, e
									.getMessage(), file, -1));
							BeansCorePlugin.log(e);
						}
					}
				}
			}
			finally {
				this.isModelPopulated = true;
				w.unlock();
				if (BeansModel.DEBUG) {
					System.out.println("Parsing of file '"
							+ this.file.getProjectRelativePath().toString() + "' took "
							+ (System.currentTimeMillis() - start) + "ms");
				}
			}
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@SuppressWarnings("unchecked")
	@Override
	public Object getAdapter(Class adapter) {
		if (adapter == IPersistableElement.class) {
			return new BeansModelElementToPersistableElementAdapter(this);
		}
		else if (adapter == IResource.class) {
			return getElementResource();
		}
		return super.getAdapter(adapter);
	}

	/**
	 * Entry into processing the contributed {@link IBeansConfigPostProcessor}.
	 */
	protected void postProcess(ProblemReporter problemReporter,
			BeanNameGenerator beanNameGenerator, final Resource resource) {

		// Create special ReaderEventListener that essentially just passes through component
		// definitions
		ReaderEventListener eventListener = new EmptyReaderEventListener() {

			// Keep the contributed model element providers
			final Map<String, IModelElementProvider> elementProviders = NamespaceUtils
					.getElementProviders();

			@Override
			public void componentRegistered(ComponentDefinition componentDefinition) {
				// make sure that all components that come through are safe for the model
				if (componentDefinition.getSource() == null) {
					if (componentDefinition instanceof BeanComponentDefinition) {
						((AbstractBeanDefinition) ((BeanComponentDefinition) componentDefinition)
								.getBeanDefinition()).setSource(new DefaultModelSourceLocation(1,
								1, resource));
					}
				}
				registerComponentDefinition(componentDefinition, elementProviders);
			}
		};

		// TODO CD do we need to check the components map as well?
		List<IBean> beansClone = new ArrayList<IBean>();
		beansClone.addAll(beans.values());

		// Run all general contributed post processors
		for (IBeansConfigPostProcessor postProcessor : BeansConfigPostProcessorFactory
				.createPostProcessor(null)) {
			postProcessor.postProcess(BeansConfigPostProcessorFactory.createPostProcessingContext(
					this, beans.values(), eventListener, problemReporter, beanNameGenerator));
		}

		// Run all post processors specific to a bean class
		for (IBean bean : beansClone) {
			String beanClassName = bean.getClassName();
			if (beanClassName != null) {
				IType type = JdtUtils.getJavaType(getElementResource().getProject(), beanClassName);
				if (type != null
						&& (Introspector.doesImplement(type, BeanFactoryPostProcessor.class
								.getName()) || Introspector.doesImplement(type,
								BeanPostProcessor.class.getName()))) {
					for (IBeansConfigPostProcessor postProcessor : BeansConfigPostProcessorFactory
							.createPostProcessor(beanClassName)) {
						postProcessor.postProcess(BeansConfigPostProcessorFactory
								.createPostProcessingContext(this, beans.values(), eventListener,
										problemReporter, beanNameGenerator));
					}
				}
			}
		}

	}

	/**
	 * Registers the given component definition with this {@link BeansConfig}'s beans and component
	 * storage.
	 */
	private void registerComponentDefinition(ComponentDefinition componentDefinition,
			Map<String, IModelElementProvider> elementProviders) {
		String uri = NamespaceUtils.getNameSpaceURI(componentDefinition);
		IModelElementProvider provider = elementProviders.get(uri);
		if (provider == null) {
			provider = DEFAULT_ELEMENT_PROVIDER;
		}
		ISourceModelElement element = provider.getElement(BeansConfig.this, componentDefinition);
		if (element instanceof IBean) {
			beans.put(element.getElementName(), (IBean) element);
		}
		else if (element instanceof IBeansComponent) {
			components.add((IBeansComponent) element);
		}
	}

	/**
	 * Returns the current {@link IResource} that is being processed.
	 */
	private IResource getCurrentResource() {
		if (currentResource != null) {
			return currentResource;
		}
		return file;
	}

	/**
	 * Implementation of {@link ReaderEventListener} which populates the current instance of
	 * {@link IBeansConfig} with data from the XML bean definition reader events.
	 */
	class BeansConfigReaderEventListener implements ReaderEventListener {

		private IBeansConfig config;

		private Resource resource;

		private Map<String, IModelElementProvider> elementProviders;

		private Map<Resource, Set<ComponentDefinition>> componentDefinitionsCache;

		private Map<Resource, Set<ImportDefinition>> importDefinitionsCache;

		private Map<Resource, Set<AliasDefinition>> aliasDefinitionsCache;

		private Map<Resource, DocumentDefaultsDefinition> defaultDefinitionsCache;

		private SourceExtractor sourceExtractor;

		public BeansConfigReaderEventListener(IBeansConfig config, Resource resource,
				SourceExtractor sourceExtractor) {
			this.config = config;
			this.resource = resource;
			this.elementProviders = NamespaceUtils.getElementProviders();
			this.componentDefinitionsCache = new HashMap<Resource, Set<ComponentDefinition>>();
			this.importDefinitionsCache = new HashMap<Resource, Set<ImportDefinition>>();
			this.aliasDefinitionsCache = new HashMap<Resource, Set<AliasDefinition>>();
			this.defaultDefinitionsCache = new HashMap<Resource, DocumentDefaultsDefinition>();
			this.sourceExtractor = sourceExtractor;
		}

		/**
		 * {@inheritDoc}
		 */
		public void defaultsRegistered(DefaultsDefinition defaultsDefinition) {
			if (defaultsDefinition instanceof DocumentDefaultsDefinition) {
				Object source = defaultsDefinition.getSource();

				if (source instanceof IModelSourceLocation) {
					Resource resource = ((IModelSourceLocation) source).getResource();
					addDefaultToCache((DocumentDefaultsDefinition) defaultsDefinition, resource);
				}
			}
		}

		/**
		 * {@inheritDoc}
		 */
		public void importProcessed(ImportDefinition importDefinition) {
			Object source = importDefinition.getSource();

			if (source instanceof IModelSourceLocation) {
				Resource resource = ((IModelSourceLocation) source).getResource();
				addImportToCache(importDefinition, resource);
			}
		}

		/**
		 * {@inheritDoc}
		 */
		public void aliasRegistered(AliasDefinition aliasDefinition) {
			Object source = aliasDefinition.getSource();

			if (source instanceof IModelSourceLocation) {
				Resource resource = ((IModelSourceLocation) source).getResource();
				addAliasToCache(aliasDefinition, resource);
			}
		}

		/**
		 * Converts the given {@link ComponentDefinition} into a corresponding
		 * {@link ISourceModelElement} via a namespace-specific {@link IModelElementProvider}. These
		 * providers are registered via the extension point
		 * <code>org.springframework.ide.eclipse.beans.core.namespaces</code>.
		 */
		public void componentRegistered(ComponentDefinition componentDefinition) {
			Object source = componentDefinition.getSource();

			// make sure nested BeanDefinitions have all the source extraction applied
			addSourceToNestedBeanDefinitions(componentDefinition);

			if (source instanceof IModelSourceLocation) {
				Resource resource = ((IModelSourceLocation) source).getResource();
				addComponentToCache(componentDefinition, resource);
			}
		}

		private void addAliasToCache(AliasDefinition aliasDefinition, Resource resource) {
			if (aliasDefinitionsCache.containsKey(resource)) {
				aliasDefinitionsCache.get(resource).add(aliasDefinition);
			}
			else {
				Set<AliasDefinition> aliasDefinitions = new LinkedHashSet<AliasDefinition>();
				aliasDefinitions.add(aliasDefinition);
				aliasDefinitionsCache.put(resource, aliasDefinitions);
			}
		}

		private void addSourceToNestedBeanDefinitions(ComponentDefinition componentDefinition) {
			if (componentDefinition instanceof CompositeComponentDefinition) {
				CompositeComponentDefinition compositeComponentDefinition = (CompositeComponentDefinition) componentDefinition;
				for (ComponentDefinition nestedComponentDefinition : compositeComponentDefinition
						.getNestedComponents()) {
					for (BeanDefinition beanDefinition : nestedComponentDefinition
							.getBeanDefinitions()) {
						if (!(beanDefinition.getSource() instanceof IModelSourceLocation)
								&& beanDefinition instanceof AbstractBeanDefinition) {
							((AbstractBeanDefinition) beanDefinition).setSource(sourceExtractor
									.extractSource(beanDefinition.getSource(), resource));
						}
					}
				}

			}
		}

		private void addComponentToCache(ComponentDefinition componentDefinition, Resource resource) {
			if (componentDefinitionsCache.containsKey(resource)) {
				componentDefinitionsCache.get(resource).add(componentDefinition);
			}
			else {
				Set<ComponentDefinition> componentDefinitions = new LinkedHashSet<ComponentDefinition>();
				componentDefinitions.add(componentDefinition);
				componentDefinitionsCache.put(resource, componentDefinitions);
			}
		}

		private void addDefaultToCache(DocumentDefaultsDefinition defaultsDefinition,
				Resource resource) {
			defaultDefinitionsCache.put(resource, defaultsDefinition);
		}

		private void addImportToCache(ImportDefinition importDefinition, Resource resource) {
			if (importDefinitionsCache.containsKey(resource)) {
				importDefinitionsCache.get(resource).add(importDefinition);
			}
			else {
				Set<ImportDefinition> importDefinitions = new LinkedHashSet<ImportDefinition>();
				importDefinitions.add(importDefinition);
				importDefinitionsCache.put(resource, importDefinitions);
			}
		}

		public void registerComponents() {

			// Start with the root resource
			defaults = defaultDefinitionsCache.get(resource);
			Set<ComponentDefinition> componentDefinitions = componentDefinitionsCache.get(resource);
			if (componentDefinitions != null) {
				for (ComponentDefinition componentDefinition : componentDefinitions) {
					registerComponentDefinition(componentDefinition, elementProviders);
				}
			}

			Set<AliasDefinition> aliasDefinitions = aliasDefinitionsCache.get(resource);
			if (aliasDefinitions != null) {
				for (AliasDefinition aliasDefinition : aliasDefinitions) {
					aliases.put(aliasDefinition.getAlias(), new BeanAlias(config, aliasDefinition));
				}
			}

			Set<ImportDefinition> importDefinitions = importDefinitionsCache.get(resource);
			if (importDefinitions != null) {
				for (ImportDefinition importDefinition : importDefinitions) {
					processImportDefinition(importDefinition, config);
				}
			}
		}

		private void processImportDefinition(ImportDefinition importDefinition, IBeansConfig config) {
			BeansImport beansImport = new BeansImport(config, importDefinition);

			if (config instanceof BeansConfig) {
				imports.add(beansImport);
			}
			else if (config instanceof ImportedBeansConfig) {
				((ImportedBeansConfig) config).addImport(beansImport);
			}

			if (((IBeansProject) getElementParent()).isImportsEnabled()) {
				Resource[] importedResources = importDefinition.getActualResources();

				for (Resource importedResource : importedResources) {
					ImportedBeansConfig importedBeansConfig = new ImportedBeansConfig(beansImport,
							importedResource, getType());
					importedBeansConfig.readConfig();
					beansImport.addImportedBeansConfig(importedBeansConfig);

					importedBeansConfig.setDefaults(defaultDefinitionsCache.get(importedResource));
					Set<ComponentDefinition> componentDefinitions = componentDefinitionsCache
							.get(importedResource);
					if (componentDefinitions != null) {
						for (ComponentDefinition componentDefinition : componentDefinitions) {
							String uri = NamespaceUtils.getNameSpaceURI(componentDefinition);
							IModelElementProvider provider = elementProviders.get(uri);
							if (provider == null) {
								provider = DEFAULT_ELEMENT_PROVIDER;
							}
							ISourceModelElement element = provider.getElement(importedBeansConfig,
									componentDefinition);
							if (element instanceof IBean) {
								importedBeansConfig.addBean((IBean) element);
							}
							else if (element instanceof IBeansComponent) {
								importedBeansConfig.addComponent((IBeansComponent) element);
							}
						}
					}

					Set<AliasDefinition> aliasDefinitions = aliasDefinitionsCache
							.get(importedResource);
					if (aliasDefinitions != null) {
						for (AliasDefinition aliasDefinition : aliasDefinitions) {
							importedBeansConfig.addAlias(new BeanAlias(importedBeansConfig,
									aliasDefinition));
						}
					}

					// Process nested imports
					Set<ImportDefinition> importDefinitions = importDefinitionsCache
							.get(importedResource);
					if (importDefinitions != null) {
						for (ImportDefinition nestedImportDefinition : importDefinitions) {
							processImportDefinition(nestedImportDefinition, importedBeansConfig);
						}
					}

					importedBeansConfig.readFinish();
				}
			}
		}
	}

	/**
	 * {@link ResourcePatternResolver} that checks if <code>.class</code> resource are being
	 * requested.
	 */
	class ClassResourceFilteringPatternResolver extends EclipsePathMatchingResourcePatternResolver
			implements ResourcePatternResolver {

		/**
		 * Creates a new {@link ClassResourceFilteringPatternResolver}
		 */
		public ClassResourceFilteringPatternResolver(IProject project) {
			super(project);
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public Resource getResource(String location) {
			// Pass package scanning through to the default pattern resolver. This is required
			// for component-scanning.
			if (location.endsWith(ClassUtils.CLASS_FILE_SUFFIX)) {
				return super.getResource(location);
			}
			return null;
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public Resource[] getResources(String locationPattern) throws IOException {
			// Pass package scanning through to the default pattern resolver. This is required
			// for component-scanning.
			if (locationPattern.endsWith(ClassUtils.CLASS_FILE_SUFFIX)) {
				return super.getResources(locationPattern);
			}
			return new Resource[0];
		}

	}

	/**
	 * SAX {@link ErrorHandler} implementation that creates {@link ValidationProblem}s for all
	 * reported problems
	 */
	class BeansConfigErrorHandler implements ErrorHandler {

		/**
		 * {@inheritDoc}
		 */
		public void warning(SAXParseException e) throws SAXException {
			problems.add(new ValidationProblem(IMarker.SEVERITY_WARNING, e.getMessage(),
					getCurrentResource(), e.getLineNumber()));
		}

		/**
		 * {@inheritDoc}
		 */
		public void error(SAXParseException e) throws SAXException {
			problems.add(new ValidationProblem(IMarker.SEVERITY_ERROR, e.getMessage(),
					getCurrentResource(), e.getLineNumber()));
		}

		/**
		 * {@inheritDoc}
		 */
		public void fatalError(SAXParseException e) throws SAXException {
			problems.add(new ValidationProblem(IMarker.SEVERITY_ERROR, e.getMessage(),
					getCurrentResource(), e.getLineNumber()));
		}
	}

	/**
	 * Implementation of the Spring tooling API {@link ProblemReporter} interface. This
	 * implementation creates {@link ValidationProblem}s for all reported problems.
	 */
	class BeansConfigProblemReporter implements ProblemReporter {

		/**
		 * {@inheritDoc}
		 */
		public void fatal(Problem problem) {
			problems.add(new ValidationProblem(IMarker.SEVERITY_ERROR, getMessage(problem),
					getCurrentResource(), getLine(problem)));
			throw new BeanDefinitionParsingException(problem);
		}

		/**
		 * {@inheritDoc}
		 */
		public void error(Problem problem) {
			problems.add(new ValidationProblem(IMarker.SEVERITY_ERROR, getMessage(problem),
					getCurrentResource(), getLine(problem)));
		}

		/**
		 * {@inheritDoc}
		 */
		public void warning(Problem problem) {
			problems.add(new ValidationProblem(IMarker.SEVERITY_WARNING, getMessage(problem),
					getCurrentResource(), getLine(problem)));
		}

		/**
		 * Returns the concatenated message form the {@link Problem} and the
		 * {@link Problem#getRootCause()}.
		 */
		private String getMessage(Problem problem) {
			StringBuffer message = new StringBuffer(problem.getMessage());
			Throwable rootCause = problem.getRootCause();
			if (rootCause != null) {

				// Retrieve nested exception
				while (rootCause.getCause() != null) {
					rootCause = rootCause.getCause();
				}
				message.append(": ");
				message.append(rootCause.getMessage());
			}
			return message.toString();
		}

		/**
		 * Returns the line of the problem by introspecting the {@link XmlSourceLocation}.
		 */
		private int getLine(Problem problem) {
			Object source = problem.getLocation().getSource();
			if (source instanceof XmlSourceLocation) {
				return ((XmlSourceLocation) source).getStartLine();
			}
			else if (source instanceof Node) {
				return LineNumberPreservingDOMParser.getStartLineNumber((Node) source);
			}
			return -1;
		}
	}

}
