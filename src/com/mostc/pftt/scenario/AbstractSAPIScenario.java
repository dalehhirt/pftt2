package com.mostc.pftt.scenario;

import com.mostc.pftt.host.Host;
import com.mostc.pftt.model.phpt.ESAPIType;
import com.mostc.pftt.model.phpt.PhpBuild;
import com.mostc.pftt.model.phpt.PhpIni;
import com.mostc.pftt.model.phpt.PhptTestCase;
import com.mostc.pftt.model.phpt.PhptSourceTestPack;
import com.mostc.pftt.model.phpt.PhptActiveTestPack;
import com.mostc.pftt.model.sapi.TestCaseGroupKey;
import com.mostc.pftt.results.ConsoleManager;
import com.mostc.pftt.results.PhptResultPackWriter;
import com.mostc.pftt.runner.AbstractPhptTestCaseRunner;
import com.mostc.pftt.runner.PhptTestPackRunner.PhptThread;

/** Different scenarios for how PHP can be run
 * 
 * CLI - command line, all that has traditionally been tested
 * Builtin-WWW
 * IIS-Express-FastCGI - using IIS Express on Windows Clients
 * IIS-FastCGI - IIS on Windows Servers
 * mod_php - using Apache's mod_php
 * 
 * @author Matt Ficken
 *
*/

public abstract class AbstractSAPIScenario extends AbstractSerialScenario {

	public static AbstractSAPIScenario getSAPIScenario(ScenarioSet scenario_set) {
		return scenario_set.getScenario(AbstractSAPIScenario.class, DEFAULT_SAPI_SCENARIO);
	}
	
	@Override
	public Class<?> getSerialKey() {
		return AbstractSAPIScenario.class;
	}
	
	/** creates a runner to run a single PhptTestCase under this SAPI scenario
	 * 
	 * @param thread
	 * @param group_key
	 * @param test_case
	 * @param twriter
	 * @param host
	 * @param scenario_set
	 * @param build
	 * @param src_test_pack
	 * @param active_test_pack
	 * @return
	 */
	public abstract AbstractPhptTestCaseRunner createPhptTestCaseRunner(PhptThread thread, TestCaseGroupKey group_key, PhptTestCase test_case, PhptResultPackWriter twriter, Host host, ScenarioSet scenario_set, PhpBuild build, PhptSourceTestPack src_test_pack, PhptActiveTestPack active_test_pack);

	public abstract boolean willSkip(PhptResultPackWriter twriter, Host host, ScenarioSet scenario_set, ESAPIType type, PhpBuild build, PhptTestCase test_case) throws Exception;
	
	public boolean willSkip(PhptResultPackWriter twriter, Host host, ScenarioSet scenario_set, ESAPIType type, PhpIni ini, PhpBuild build, PhptTestCase test_case) throws Exception {
		return AbstractPhptTestCaseRunner.willSkip(twriter, host, scenario_set, type, ini, build, test_case);
	}
	
	public void close(boolean debug) {
		
	}

	public abstract int getTestThreadCount(Host host);

	public abstract ESAPIType getSAPIType();

	/** creates a key to group test cases under
	 * 
	 * each key has a unique phpIni and/or ENV vars
	 * 
	 * Web Server SAPIs require grouping test cases by keys because a new WebServerInstance for each PhpIni, but
	 * a WebServerInstance can be used to run multiple test cases. this will boost performance.
	 * 
	 * @param cm
	 * @param host
	 * @param build
	 * @param scenario_set
	 * @param active_test_pack
	 * @param test_case
	 * @param group_key
	 * @return
	 * @throws Exception
	 */
	public abstract TestCaseGroupKey createTestGroupKey(ConsoleManager cm, Host host, PhpBuild build, ScenarioSet scenario_set, PhptActiveTestPack active_test_pack, PhptTestCase test_case, TestCaseGroupKey group_key) throws Exception;

} // end public abstract class AbstractSAPIScenario