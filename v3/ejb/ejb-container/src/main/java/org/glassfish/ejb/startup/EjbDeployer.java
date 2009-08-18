/*
 * The contents of this file are subject to the terms 
 * of the Common Development and Distribution License 
 * (the License).  You may not use this file except in
 * compliance with the License.
 * 
 * You can obtain a copy of the license at 
 * https://glassfish.dev.java.net/public/CDDLv1.0.html or
 * glassfish/bootstrap/legal/CDDLv1.0.txt.
 * See the License for the specific language governing 
 * permissions and limitations under the License.
 * 
 * When distributing Covered Code, include this CDDL 
 * Header Notice in each file and include the License file 
 * at glassfish/bootstrap/legal/CDDLv1.0.txt.  
 * If applicable, add the following below the CDDL Header, 
 * with the fields enclosed by brackets [] replaced by
 * you own identifying information: 
 * "Portions Copyrighted [year] [name of copyright owner]"
 * 
 * Copyright 2006 Sun Microsystems, Inc. All rights reserved.
 */

package org.glassfish.ejb.startup;

import com.sun.enterprise.config.serverbeans.Domain;
import com.sun.enterprise.deployment.Application;
import com.sun.enterprise.deployment.EjbBundleDescriptor;
import com.sun.enterprise.deployment.BundleDescriptor;
import com.sun.enterprise.deployment.EjbDescriptor;
import com.sun.enterprise.deployment.ManagedBeanDescriptor;
import com.sun.enterprise.security.PolicyLoader;
import com.sun.enterprise.security.SecurityUtil;
import com.sun.enterprise.security.util.IASSecurityException;
import com.sun.enterprise.container.common.spi.util.ComponentEnvManager;
import com.sun.logging.LogDomains;
import org.glassfish.internal.api.ServerContext;
import org.glassfish.server.ServerEnvironmentImpl;
import org.glassfish.api.deployment.DeploymentContext;
import org.glassfish.api.deployment.MetaData;
import org.glassfish.api.deployment.OpsParams;
import org.glassfish.api.deployment.DeployCommandParameters;
import org.glassfish.deployment.common.DeploymentException;
import org.glassfish.javaee.core.deployment.JavaEEDeployer;
import org.glassfish.ejb.spi.CMPDeployer;
import org.glassfish.ejb.spi.CMPService;
import org.jvnet.hk2.annotations.Inject;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.component.Habitat;
import com.sun.ejb.codegen.StaticRmiStubGenerator;

import java.util.*;
import java.util.logging.Level;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.glassfish.ejb.security.factory.EJBSecurityManagerFactory;

/**
 * Ejb module deployer.
 *
 */
@Service
public class EjbDeployer
    extends JavaEEDeployer<EjbContainerStarter, EjbApplication> {

    @Inject
    protected ServerContext sc;

    @Inject
    protected Domain domain;

    @Inject
    protected ServerEnvironmentImpl env;

    @Inject
    protected Habitat habitat;
    
    @Inject
    protected PolicyLoader policyLoader;
    
    @Inject
    protected EJBSecurityManagerFactory ejbSecManagerFactory;

    @Inject
    private ComponentEnvManager compEnvManager;

    protected CMPDeployer cmpDeployer;

    // Property used to persist unique id across server restart.
    private static final String APP_UNIQUE_ID_PROP = "org.glassfish.ejb.container.application_unique_id";

    private AtomicLong uniqueIdCounter;
    
    private static final Logger _logger =
                LogDomains.getLogger(EjbDeployer.class, LogDomains.EJB_LOGGER);

    /**
     * Constructor
     */
    public EjbDeployer() {

        // Seed a counter used for ejb application unique id generation.
        uniqueIdCounter = new AtomicLong(System.currentTimeMillis());

    }


    protected String getModuleType () {
        return "ejb";
    }

    @Override
    public MetaData getMetaData() {
        return new MetaData(false,
                new Class[] {EjbBundleDescriptor.class}, new Class[] {Application.class});
    }

    @Override
    public EjbApplication load(EjbContainerStarter containerStarter, DeploymentContext dc) {
        super.load(containerStarter, dc);

        EjbBundleDescriptor ejbBundle = dc.getModuleMetaData(EjbBundleDescriptor.class);
        
        if( ejbBundle == null ) {
            throw new RuntimeException("Null EjbBundleDescriptor " + dc.getSourceDir() + " in EjbDeployer.load()");
        }

        // Get application-level properties (*not* module-level)
        Properties appProps = dc.getAppProps();

        long uniqueAppId;

        if( !appProps.containsKey(APP_UNIQUE_ID_PROP)) {

            // This is the first time load is being called for any ejb module in an
            // application, so generate the unique id.

            uniqueAppId = getNextEjbAppUniqueId();
            appProps.setProperty(APP_UNIQUE_ID_PROP, uniqueAppId + "");
        } else {
            uniqueAppId = Long.parseLong(appProps.getProperty(APP_UNIQUE_ID_PROP));         
        }

        Application app = ejbBundle.getApplication();

        if( !app.isUniqueIdSet() ) {
            // This will set the unique id for all EJB components in the application.
            // If there are multiple ejb modules in the app, we'll only call it once
            // for the first ejb module load().  All the old
            // .xml processing for unique-id in the sun-* descriptors is removed so
            // this is the only place where Application.setUniqueId() should be called.
            app.setUniqueId(uniqueAppId);
        }

        if (ejbBundle.containsCMPEntity()) {
            CMPService cmpService = habitat.getByContract(CMPService.class);
            if (cmpService == null) {
                throw new RuntimeException("CMP Module is not available");
            } else if (!cmpService.isReady()) {
                throw new RuntimeException("CMP Module is not initialized");
            }
        }


        EjbApplication ejbApp = new EjbApplication(ejbBundle, dc, dc.getClassLoader(), habitat,
                                                   ejbSecManagerFactory);

        try {
            compEnvManager.bindToComponentNamespace(ejbBundle);

        } catch(Exception e) {
            throw new RuntimeException("Exception registering ejb bundle level resources", e);
        }

        ejbApp.loadContainers(dc);

        return ejbApp;
    }

    public void unload(EjbApplication ejbApplication, DeploymentContext dc) {

        EjbBundleDescriptor ejbBundle = ejbApplication.getEjbBundleDescriptor();

        try {
            compEnvManager.unbindFromComponentNamespace(ejbBundle);          
        } catch(Exception e) {
             _logger.log( Level.WARNING, "Error unbinding ejb bundle " +
                     ejbBundle.getModuleName() + " dependency namespace", e);
        }


        // All the other work is done in EjbApplication. 

    }

    /**
     * Clean any files and artifacts that were created during the execution
     * of the prepare method.
     *
     * @param dc deployment context
     */
    public void clean(DeploymentContext dc) {
        
        // Both undeploy and shutdown scenarios are
        // handled directly in EjbApplication.shutdown.

        // But CMP drop tables should be handled here.

        OpsParams params = dc.getCommandParameters(OpsParams.class);
        if (params.origin == OpsParams.Origin.undeploy) {

            cmpDeployer = habitat.getByContract(CMPDeployer.class);
            if (cmpDeployer != null) {
                cmpDeployer.clean(dc);
            }

            //Removing EjbSecurityManager for undeploy case
            String appName = params.name();
            String[] contextIds =
                    ejbSecManagerFactory.getContextsForApp(appName, false);
            for (String contextId : contextIds) {
                try {
                    SecurityUtil.removePolicy(contextId);
                } catch (IASSecurityException ex) {
                    _logger.log( Level.WARNING,"Error removing the policy file " +
                            "for application " + appName + " " + ex);
                }
            }
        }


    }

    /**
     * Use this method to generate any ejb-related artifacts for the module
     */
    @Override
    protected void generateArtifacts(DeploymentContext dc)
            throws DeploymentException {

        OpsParams params = dc.getCommandParameters(OpsParams.class);
        if (params.origin != OpsParams.Origin.deploy) {
            return;
        }

        EjbBundleDescriptor bundle = dc.getModuleMetaData(EjbBundleDescriptor.class);
        policyLoader.loadPolicy();
        if (bundle != null) {
            for (EjbDescriptor desc : bundle.getEjbs()) {
                //create security manager for each EJB but don't register
                this.ejbSecManagerFactory.createManager(desc, false);
            }
        }

        DeployCommandParameters dcp =
                dc.getCommandParameters(DeployCommandParameters.class);
        boolean generateRmicStubs = dcp.generatermistubs;
        if( generateRmicStubs ) {
            StaticRmiStubGenerator staticStubGenerator = new StaticRmiStubGenerator();
            try {
                staticStubGenerator.ejbc(habitat, dc);
            } catch(Exception e) {
                throw new DeploymentException("Static RMI-IIOP Stub Generation exception for " +
                        dc.getSourceDir(), e);
            }
        }

        if (bundle == null || !bundle.containsCMPEntity()) {
            // bundle WAS null in a war file where we do not support CMPs
            return;
        }

        cmpDeployer = habitat.getByContract(CMPDeployer.class);
        if (cmpDeployer == null) {
            throw new DeploymentException("No CMP Deployer is available to deploy this module");
        }
        cmpDeployer.deploy(dc);   


    }

    private long getNextEjbAppUniqueId() {
        long next = uniqueIdCounter.incrementAndGet();

        // This number represents the base unique id for each ejb application.
        // It is used to generate an id for each ejb component that is
        // guaranteed to be unique across all the applications deployed to a
        // particular stand-alone server instance or DAS.
        //
        // The unique id is 64 bits, with the low-order 16 bits zeroed out.
        // Component ids are selected from these low-order bits, allowing a
        // maximum of 2^16 EJB components per application.
        //
        // The initial number is seeded from System.currentTimeMillis() the
        // first time this class is instantiated after a server start.
        // Since this epoch value is relative to 1970, even after left-shifting
        // 16 bits, the number of remaining milliseconds won't run out until
        // the year 10889.   This scheme also assumes that for the lifetime
        // of the JVM for a given server, there aren't more individual
        // ejb application deployments than elapsed milliseconds, since the
        // next time the server starts it will simply seed from
        // currentTimeMillis() again rather than remembering the largest unique
        // id that was used the last time the server ran.  

        return next << 16;
    }


    
}
