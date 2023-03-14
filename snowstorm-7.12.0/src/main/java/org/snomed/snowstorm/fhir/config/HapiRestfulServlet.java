package org.snomed.snowstorm.fhir.config;

import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snomed.snowstorm.fhir.services.FHIRCodeSystemProvider;
import org.snomed.snowstorm.fhir.services.FHIRConceptMapProvider;
import org.snomed.snowstorm.fhir.services.FHIRMedicationProvider;
import org.snomed.snowstorm.fhir.services.FHIRStructureDefinitionProvider;
import org.snomed.snowstorm.fhir.services.FHIRTerminologyCapabilitiesProvider;
import org.snomed.snowstorm.fhir.services.FHIRValueSetProvider;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.LenientErrorHandler;
import ca.uhn.fhir.parser.StrictErrorHandler;
import ca.uhn.fhir.rest.api.EncodingEnum;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.RestfulServer;

public class HapiRestfulServlet extends RestfulServer {

	private static final long serialVersionUID = 1L;
	
	private final Logger logger = LoggerFactory.getLogger(getClass());

	/**
	 * The initialize method is automatically called when the servlet is starting up, so it can be used to configure the
	 * servlet to define resource providers, or set up configuration, interceptors, etc.
	 */
	@Override
	protected void initialize() throws ServletException {
		final WebApplicationContext applicationContext =
				WebApplicationContextUtils.getWebApplicationContext(this.getServletContext());

		setDefaultResponseEncoding(EncodingEnum.JSON);

		final FhirContext ctxt = FhirContext.forR4();
		final LenientErrorHandler delegateHandler = new LenientErrorHandler();
		ctxt.setParserErrorHandler(new StrictErrorHandler() {
			@Override
			public void unknownAttribute(IParseLocation theLocation, String theAttributeName) {
				delegateHandler.unknownAttribute(theLocation, theAttributeName);
			}
			@Override
			public void unknownElement(IParseLocation theLocation, String theElementName) {
				delegateHandler.unknownElement(theLocation, theElementName);
			}
			@Override
			public void unknownReference(IParseLocation theLocation, String theReference) {
				delegateHandler.unknownReference(theLocation, theReference);
			}
		});
		setFhirContext(ctxt);

		/*
		 * The servlet defines any number of resource providers, and configures itself to use them by calling
		 * setResourceProviders()
		 */
		List<IResourceProvider> resourceProviders = new ArrayList<>();
		FHIRCodeSystemProvider csp = applicationContext.getBean(FHIRCodeSystemProvider.class);
		FHIRValueSetProvider vsp = applicationContext.getBean(FHIRValueSetProvider.class);
		FHIRConceptMapProvider cmp = applicationContext.getBean(FHIRConceptMapProvider.class);
		FHIRMedicationProvider mp = applicationContext.getBean(FHIRMedicationProvider.class);
		FHIRStructureDefinitionProvider sd = applicationContext.getBean(FHIRStructureDefinitionProvider.class);
		
		resourceProviders.add(csp);
		resourceProviders.add(vsp);
		resourceProviders.add(cmp);
		resourceProviders.add(mp);
		resourceProviders.add(sd);
		
		setResourceProviders(resourceProviders);
		
		FHIRTerminologyCapabilitiesProvider tcp = applicationContext.getBean(FHIRTerminologyCapabilitiesProvider.class);
		setServerConformanceProvider(tcp);
		
		// Now register interceptors
		RootInterceptor interceptor = new RootInterceptor();
		registerInterceptor(interceptor);
		
		logger.info("FHIR Resource providers and interceptors registered");
	}
}

