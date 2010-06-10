/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 1997-2009 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package admin;


import java.io.File;
import javax.xml.xpath.XPathConstants;

/*
 * Dev test for create/delete/list cluster
 * @author Bhakti Mehta
 */
public class ClusterTest extends AdminBaseDevTest {

    public static void main(String[] args) {
        new ClusterTest().run();
    }

    @Override
    protected String getTestName() {
        return "cluster";
    }

    @Override
    protected String getTestDescription() {
        return "Unit test for create/delete/list cluster";
    }

    public void run() {
        String xpathExpr = "count" + "(" + "/domain/clusters/cluster" + ")";
        double startingNumberOfClusters = 0.0;
        Object o = evalXPath(xpathExpr, XPathConstants.NUMBER);
        if(o instanceof Double) {
            startingNumberOfClusters = (Double)o;
        }

        report("create-cluster", asadmin("create-cluster", "cl1"));

        //create-cluster using existing config
        report("create-cluster-with-config", asadmin("create-cluster",
                "--config", "cl1-config",
                "cl2"));

        //check for duplicates
        report("create-cluster-duplicates", !asadmin("create-cluster", "cl1"));

        //create-cluster using non existing config
        report("create-cluster-nonexistent-config", !asadmin("create-cluster",
                "--config", "junk-config",
                "cl3"));

        //create-cluster using systemproperties
        report("create-cluster-system-props", asadmin("create-cluster",
                "--systemproperties", "foo=bar",
                "cl4"));

        //evaluate using xpath that there are 3 elements in the domain.xml

        o = evalXPath(xpathExpr, XPathConstants.NUMBER);
        System.out.println("No of cluster elements in cluster: " + o);
        if(o instanceof Double) {
            report("evaluation-xpath-create-cluster", o.equals(new Double(3.0 + startingNumberOfClusters)));
        }
        else {
            report("evaluation-xpath-create-cluster", false);
        }

        //list-clusters
        report("list-clusters", asadmin("list-clusters"));
        testDeleteClusterWithInstances();
        testEndToEndDemo();
        cleanup();
        stat.printSummary();
    }

    private void  testDeleteClusterWithInstances(){
        //test for issue 12172
        final String iname = "xyz1";
        final String cluster = "cl7";
        final String testName = "issue-12172-";
        report (testName +"create-cl",asadmin("create-cluster", cluster)) ;
        report(testName +"create-l-i",asadmin("create-local-instance","--cluster",cluster, iname));
        report(testName+"delete-cl-with-instance", !asadmin("delete-cluster",cluster));
        report(testName+"delete-l-i",asadmin("delete-local-instance",iname));
        //check if there is no server-ref property in the cluster element
        report(testName +"check-serverRef", !asadmin("get", "clusters.cluster."+cluster+".server-ref." + iname));
        report(testName+"delete-cl-no-ins", asadmin("delete-cluster",cluster));
    }

    /*
     * This is a test based on the MS1 demo of the basic clustering infrastructure.
     * See http://wiki.glassfish.java.net/Wiki.jsp?page=3.1MS1ClusteringDemo
     */
    private void testEndToEndDemo() {
        final String tn = "end-to-end-";

        final String i1url = "http://localhost:18080/";
        final String i1murl = "http://localhost:14848/management/domain/";
        final String i2url = "http://localhost:28080/";
        final String i2murl = "http://localhost:24848/management/domain/";
        final String dasmurl = "http://localhost:4848/management/domain/";

        // create a cluster and two instances
        report(tn + "create-cluster", asadmin("create-cluster", "eec1"));
        report(tn + "create-local-instance1", asadmin("create-local-instance",
                "--cluster", "eec1", "--systemproperties",
                "HTTP_LISTENER_PORT=18080:HTTP_SSL_LISTENER_PORT=18181:IIOP_SSL_LISTENER_PORT=13800:" +
                "IIOP_LISTENER_PORT=13700:JMX_SYSTEM_CONNECTOR_PORT=17676:IIOP_SSL_MUTUALAUTH_PORT=13801:" +
                "JMS_PROVIDER_PORT=18686:ASADMIN_LISTENER_PORT=14848", "eein1"));
        report(tn + "create-local-instance2", asadmin("create-local-instance",
                "--cluster", "eec1", "--systemproperties",
                "HTTP_LISTENER_PORT=28080:HTTP_SSL_LISTENER_PORT=28181:IIOP_SSL_LISTENER_PORT=23800:" +
                "IIOP_LISTENER_PORT=23700:JMX_SYSTEM_CONNECTOR_PORT=27676:IIOP_SSL_MUTUALAUTH_PORT=23801:" +
                "JMS_PROVIDER_PORT=28686:ASADMIN_LISTENER_PORT=24848", "eein2"));

        // deploy an application to the cluster
        //File webapp = new File("resources", "helloworld.war");
        //report(tn + "deploy", asadmin("deploy", "--target", "eec1", webapp.getAbsolutePath()));

        // start the instances
        report(tn + "start-local-instance1", asadmin("start-local-instance", "eein1"));
        report(tn + "start-local-instance2", asadmin("start-local-instance", "eein2"));

        // check that the instances are there
        report(tn + "list-instances", asadmin("list-instances"));
        report(tn + "getindex1", matchString("GlassFish Enterprise Server", getURL(i1url)));
        report(tn + "getindex2", matchString("GlassFish Enterprise Server", getURL(i2url)));
        //report(tn + "getapp1", matchString("Hello", getURL(i1url + "helloworld/hi.jsp")));
        //report(tn + "getapp2", matchString("Hello", getURL(i2url + "helloworld/hi.jsp")));

        report(tn + "getREST1", matchString("server/eein1/property", getURL(i1murl + "servers/server/eein1")));
        report(tn + "getREST1a", !matchString("eein2", getURL(i1murl + "servers/server")));
        report(tn + "getREST2", matchString("server/eein2/property", getURL(i2murl + "servers/server/eein2")));
        String s = getURL(dasmurl + "servers/server");
        report(tn + "getREST3a", matchString("eein1", s));
        report(tn + "getREST3b", matchString("eein2", s));
        report(tn + "getREST3c", matchString("server", s));
        
        // dynamic configuration
        
        // create several resources
        report(tn + "create-jdbc-connection-pool", asadmin("create-jdbc-connection-pool",
                "--datasourceclassname", "org.apache.derby.jdbc.ClientDataSource",
                "--restype", "javax.sql.XADataSource",
                "--target", "eec1", "sample_jdbc_pool"));
        report(tn + "create-iiop-listener", asadmin("create-iiop-listener",
                "--target", "eec1",
                "--listeneraddress", "192.168.1.100",
                "--iiopport", "1400", "sample_iiop_listener"));
        report(tn + "create-connector-connection-pool", asadmin("create-connector-connection-pool",
                "--target", "eec1",
                "--raname", "jmsra",
                "--connectiondefinition", "javax.jms.QueueConnectionFactory",
                "jms/qConnPool"));

        // check that the resources have been created on all instances
        report(tn + "jdbc-check1", matchString("sample_jdbc_pool",
                getURL(i1murl + "resources/jdbc-connection-pool")));
        report(tn + "jdbc-check2", matchString("sample_jdbc_pool",
                getURL(i2murl + "resources/jdbc-connection-pool")));
        report(tn + "iiop-check1", matchString("sample_iiop_listener",
                getURL(i1murl + "configs/config/eec1-config/iiop-service/iiop-listener")));
        report(tn + "iiop-check2", matchString("sample_iiop_listener",
                getURL(i2murl + "configs/config/eec1-config/iiop-service/iiop-listener")));
        report(tn + "cp-check1", matchString("qConnPool",
                getURL(i1murl + "resources/connector-connection-pool")));
        report(tn + "cp-check2", matchString("qConnPool",
                getURL(i2murl + "resources/connector-connection-pool")));

        // try to create a resource on only one instance - should fail
        report(tn + "create-connector-connection-pool-instance",
               !asadmin("create-connector-connection-pool", "--target", "eein2",
               "--raname", "jmsra",
               "--connectiondefinition", "javax.jms.QueueConnectionFactory",
               "jms/instanceOnlyConnPool"));

        // delete the resources
        report(tn + "delete-jdbc-connection-pool", asadmin("delete-jdbc-connection-pool",
                "--target", "eec1", "sample_jdbc_pool"));
        report(tn + "delete-iiop-listener", asadmin("delete-iiop-listener",
                "--target", "eec1", "sample_iiop_listener"));
        report(tn + "delete-connector-connection-pool", asadmin("delete-connector-connection-pool",
                "--target", "eec1", "jms/qConnPool"));

        // check that the resources have been deleted
        report(tn + "jdbc-del-check1", !matchString("sample_jdbc_pool",
                getURL(i1murl + "resources/resources/jdbc-resource")));
        report(tn + "jdbc-del-check2", !matchString("sample_jdbc_pool",
                getURL(i2murl + "resources/resources/jdbc-resource")));
        report(tn + "iiop-del-check1", !matchString("sample_iiop_listener",
                getURL(i1murl + "configs/config/eec1-config/iiop-service/iiop-listener")));
        report(tn + "iiop-del-check2", !matchString("sample_iiop_listener",
                getURL(i2murl + "configs/config/eec1-config/iiop-service/iiop-listener")));
        report(tn + "cp-del-check1", !matchString("qConnPool",
                getURL(i1murl + "resources/connector-connection-pool")));
        report(tn + "cp-del-check2", !matchString("qConnPool",
                getURL(i2murl + "resources/connector-connection-pool")));

        // stop the instances
        report(tn + "stop-local-instance1", asadmin("stop-local-instance", "eein1"));
        report(tn + "stop-local-instance2", asadmin("stop-local-instance", "eein2"));

        // delete the instances and the cluster
        report(tn + "delete-local-instance1", asadmin("delete-local-instance", "eein1"));
        report(tn + "delete-local-instance2", asadmin("delete-local-instance", "eein2"));
        report(tn + "delete-cluster", asadmin("delete-cluster", "eec1"));

    }

    @Override
    public void cleanup() {
        //Cleanup the code so that tests run successfully next time
        report("delete-cl1", asadmin("delete-cluster", "cl1"));
        report("delete-cl2", asadmin("delete-cluster", "cl2"));
        report("delete-cl3", !asadmin("delete-cluster", "cl3")); // should not have been created
        report("delete-cl4", asadmin("delete-cluster", "cl4"));

        AsadminReturn ret = asadminWithOutput("list-clusters");
        String s = (ret.out == null) ? "" : ret.out.trim();

        // make sure none of OUR clusters are in there.  Other clusters that are
        // in the user's domain are OK...

        boolean success = s.indexOf("cl1") < 0
                && s.indexOf("cl2") < 0
                && s.indexOf("cl3") < 0
                && s.indexOf("cl4") < 0;

        System.out.println("list-clusters returned:");
        System.out.println(s);

		if(!success) {
        	System.out.println("IT 12153 is apparently not fixed!!  \nLet's try a restart and call list-clusters again...");
			asadmin("restart-domain");
			asadmin("list-clusters");
		}
	else	
        report("verify-list-of-zero-clusters", success);
    }
}
