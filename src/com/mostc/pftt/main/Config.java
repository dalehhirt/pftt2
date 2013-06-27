package com.mostc.pftt.main;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.commons.net.ftp.FTPClient;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.runtime.metaclass.MissingMethodExceptionNoStack;
import org.codehaus.groovy.runtime.metaclass.MissingPropertyExceptionNoStack;
import org.columba.ristretto.smtp.SMTPProtocol;

import com.github.mattficken.io.IOUtil;
import com.github.mattficken.io.StringUtil;
import com.mostc.pftt.host.AHost;
import com.mostc.pftt.host.LocalHost;
import com.mostc.pftt.model.app.PhpUnitSourceTestPack;
import com.mostc.pftt.model.app.PhpUnitTestCase;
import com.mostc.pftt.model.core.PhpBuild;
import com.mostc.pftt.model.core.PhpIni;
import com.mostc.pftt.model.core.PhptSourceTestPack;
import com.mostc.pftt.model.core.PhptTestCase;
import com.mostc.pftt.model.ui.UITestPack;
import com.mostc.pftt.results.ConsoleManager;
import com.mostc.pftt.results.EPrintType;
import com.mostc.pftt.results.ITestResultReceiver;
import com.mostc.pftt.results.PhpResultPackWriter;
import com.mostc.pftt.scenario.EScenarioSetPermutationLayer;
import com.mostc.pftt.scenario.Scenario;
import com.mostc.pftt.scenario.ScenarioSet;
import com.mostc.pftt.scenario.app.JoomlaScenario;

import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovyObject;
import groovy.lang.MetaMethod;

/** Handles loading a configuration (hosts, scenarios, credentials, etc...) from a groovy script.
 * 
 * This allows for loading scenarios and hosts without creating a special configuration format, its just executable code.
 * 
 * @see Config#getHosts
 * @see Config#loadConfigFromFile
 * 
 * An example configuration file:
 * def scenarios() {
 * 	// provide the scenarios to run... these are automatically permuted into the ScenarioSets which are actually run
 *  //
 *  // you can create your own scenarios here that override existing Scenarios to change their behavior, settings, etc...
 * }
 * def hosts() {
 * 		[
 * 			// connect to a host using ssh
 *         //
 *         // can test multiple operating systems, one host for each OS (so multiple hosts)
 * 			new SSHHost("192.168.1.1", "administrator", "password01!")
 * 		]
 * }
 * def getPhpUnitSourceTestPack() {
 *	// PhpUnit test-pack
 * }
 * def getUITestPack() {
 *  // UI test-pack
 * }
 * def scenario_sets() {
 * 		[
 * 			// provide address of a MySQL server
 * 			new MySQLScenario()
 * 		]
 * }
 * def configure_smtp(def smtp_client) {
 * 		// specify smtp server and credentials 
 * }
 * def configure_ftp_client(def ftp_client) {
 * 		// specify ftp server and credentials
 * }
 * def describe() {
 * 		// return description String of this config file
 * }
 * def processConsoleOptions(List) {
 * 		// add to/remove/edit console options (mainly for task configuration files)
 * }
 * def prepareENV(map) {
 * 		// edit environment variables (HashMap)
 * }
 * def prepareINI(ini) {
 * 		// edit INI (PhpIni)
 * }
 * def noScenarios() {
 * 		// return string fragments of names to skip when calculating scenario permutations
 * }
 * def processPHPT(PhptTestCase test_case) {
 * }
 * def processPhpUnit(PhpUnitTestCase test_case) {
 * }
 * def processPHPTTestPack
 * def processPhpUnitTestPack
 * 
 * 
 * @author Matt Ficken
 * 
 */

public final class Config implements IENVINIFilter {
	public static final String HOSTS_METHOD = "hosts";
	public static final String SCENARIO_SETS_METHOD = "scenario_sets";
	public static final String SCENARIOS_METHOD = "scenarios";
	public static final String GET_PHP_UNIT_SOURCE_TEST_PACK_METHOD = "getPhpUnitSourceTestPack";
	public static final String GET_UI_TEST_PACK_METHOD = "getUITestPack";
	public static final String CONFIGURE_SMTP_METHOD = "configure_smtp";
	public static final String CONFIGURE_FTP_CLIENT_METHOD = "configure_ftp_client";
	public static final String DESCRIBE_METHOD_NAME = "describe";
	public static final String PROCESS_CONSOLE_OPTIONS_METHOD_NAME = "processConsoleOptions";
	public static final String NOT_SCENARIOS_METHOD_NAME = "notScenarios";
	public static final String PREPARE_ENV_METHOD_NAME = "prepareENV";
	public static final String PREPARE_INI_METHOD_NAME = "prepareINI";
	public static final String PROCESS_PHPT_METHOD_NAME = "processPHPT";
	public static final String PROCESS_PHPUNIT_METHOD_NAME = "processPhpUnit";
	public static final String PROCESS_PHPT_TEST_PACK_METHOD_NAME = "processPHPTTestPack";
	public static final String PROCESS_PHPUNIT_TEST_PACK_METHOD_NAME = "processPhpUnitTestPack";
	//
	protected final LinkedList<AHost> hosts;
	protected final LinkedList<Scenario> scenarios;
	protected final LinkedList<ScenarioSet> scenario_sets;
	protected final HashMap<EScenarioSetPermutationLayer,List<ScenarioSet>> permuted_scenario_sets;
	protected final HashMap<String,ArrayList<MethodImpl>> by_method_name;
	
	protected static class MethodImpl {
		protected final String filename;
		protected final GroovyObject go;
		
		protected MethodImpl(String filename, GroovyObject go) {
			this.filename = filename;
			this.go = go;
		}
	}
	
	protected Config() {
		hosts = new LinkedList<AHost>();
		scenario_sets = new LinkedList<ScenarioSet>();
		scenarios = new LinkedList<Scenario>();
		
		by_method_name = new HashMap<String,ArrayList<MethodImpl>>();
		
		permuted_scenario_sets = new HashMap<EScenarioSetPermutationLayer,List<ScenarioSet>>();
	}
	
	public List<AHost> getHosts() {
		return hosts;
	}
	
	public void showHelpMessages() {
		// TODO get helpMsg() from config files
		//      ex: list_builtin_functions
		//      ex: internal_examples - copy to internal
	}
	
	public void processPHPTTestPack(PhptSourceTestPack test_pack, PhpResultPackWriter twriter, PhpBuild build) {
		ArrayList<MethodImpl> methods = by_method_name.get(PROCESS_PHPT_TEST_PACK_METHOD_NAME);
		if (methods==null)
			return;
		for ( MethodImpl m : methods ) {
			invokeMethod(null, null, m.go, PROCESS_PHPT_TEST_PACK_METHOD_NAME, new Object[]{test_pack, twriter, build}, null);
		}
	}
	
	public void processPHPT(PhptTestCase test_case) {
		ArrayList<MethodImpl> methods = by_method_name.get(PROCESS_PHPT_METHOD_NAME);
		if (methods==null)
			return;
		for ( MethodImpl m : methods ) {
			invokeMethod(null, null, m.go, PROCESS_PHPT_METHOD_NAME, test_case, null);
		}
	}
	
	public void processPhpUnitTestPack(PhpUnitSourceTestPack test_pack, ITestResultReceiver twriter, PhpBuild build) {
		ArrayList<MethodImpl> methods = by_method_name.get(PROCESS_PHPUNIT_TEST_PACK_METHOD_NAME);
		if (methods==null)
			return;
		for ( MethodImpl m : methods ) {
			invokeMethod(null, null, m.go, PROCESS_PHPUNIT_TEST_PACK_METHOD_NAME, new Object[]{test_pack, twriter, build}, null);
		}
	}
	
	public void processPhpUnit(PhpUnitTestCase test_case) {
		ArrayList<MethodImpl> methods = by_method_name.get(PROCESS_PHPUNIT_METHOD_NAME);
		if (methods==null)
			return;
		for ( MethodImpl m : methods ) {
			invokeMethod(null, null, m.go, PROCESS_PHPUNIT_METHOD_NAME, test_case, null);
		}
	}
	
	@Override
	public void prepareEnv(ConsoleManager cm, Map<String,String> env) {
		ArrayList<MethodImpl> methods = by_method_name.get(PREPARE_ENV_METHOD_NAME);
		if (methods==null)
			return;
		for ( MethodImpl m : methods ) {
			invokeMethod(null, cm, m.go, PREPARE_ENV_METHOD_NAME, env, null);
		}
	}
	
	@Override
	public void prepareIni(ConsoleManager cm, PhpIni ini) {
		ArrayList<MethodImpl> methods = by_method_name.get(PREPARE_INI_METHOD_NAME);
		if (methods==null)
			return;
		for ( MethodImpl m : methods ) {
			invokeMethod(null, cm, m.go, PREPARE_INI_METHOD_NAME, ini, null);
		}
	}
	
	public boolean processConsoleOptions(ConsoleManager cm, final List<String> options) {
		ArrayList<MethodImpl> methods = by_method_name.get(PROCESS_CONSOLE_OPTIONS_METHOD_NAME);
		if (methods==null)
			return false;
		final List<String> first_options = new ArrayList<String>(4);
		@SuppressWarnings("serial")
		List<String> _options = new ArrayList<String>(options.size()) {
				public boolean add(String option) {
					return first_options.add(option) && super.add(option);
				}
				public String remove(int i) {
					String s = super.get(i);
					return remove(s) ? s : null;
				}
				public boolean remove(String s) {
					return options.remove(s) && super.remove(s);
				}
			};
		_options.addAll(options);
		for ( MethodImpl m : methods ) {
			invokeMethod(null, cm, m.go, PROCESS_CONSOLE_OPTIONS_METHOD_NAME, _options, m.filename);
		}
		final boolean change = _options.equals(options);
		options.addAll(0, first_options);
		return !change;
	}
	
	public static List<ScenarioSet> not(List<String> not_scenarios, List<ScenarioSet> scenario_sets) {
		if (not_scenarios==null||not_scenarios.isEmpty())
			return scenario_sets;
		Iterator<ScenarioSet> it = scenario_sets.iterator();
		String nv;
		while (it.hasNext()) {
			nv = it.next().getName().toLowerCase();
			for ( String str : not_scenarios ) {
				if (nv.contains(str.toLowerCase())) {
					// match, remove it
					it.remove();
					break;
				}
			}
		} // end while
		return scenario_sets;
	}
	
	@SuppressWarnings("unchecked")
	public List<String> getNotScenarios(ConsoleManager cm) {
		ArrayList<MethodImpl> methods = by_method_name.get(NOT_SCENARIOS_METHOD_NAME);
		if (methods==null)
			return new ArrayList<String>(0);
		ArrayList<String> strings = new ArrayList<String>(methods.size());
		for ( MethodImpl m : methods )
			strings.addAll((List<String>)invokeMethod(List.class, cm, m.go, NOT_SCENARIOS_METHOD_NAME, null, m.filename));
		return strings;
	}
	
	/** returns the ScenarioSets from this configuration
	 * 
	 * this is the ScenarioSets defined in the configuration file(s)'s 
	 * scenario_sets() functions AND all valid permutations of Scenarios
	 * provided by the scenarios() functions.
	 * 
	 * @param cm -
	 * @param layer - PHP_CORE (PHPT) tests can have multiple database scenarios per ScenarioSet, while
	 * application tests can ony have 1 database scenario per ScenarioSet.
	 * @return
	 */
	public List<ScenarioSet> getScenarioSets(ConsoleManager cm, EScenarioSetPermutationLayer layer) {
		if (layer==null)
			layer = EScenarioSetPermutationLayer.PHP_CORE; // fallback
		
		List<ScenarioSet> this_scenario_sets = permuted_scenario_sets.get(layer);
		if (this_scenario_sets!=null)
			return not(getNotScenarios(cm), this_scenario_sets);
		
		this_scenario_sets = not(getNotScenarios(cm), permuteScenarioSets(cm, layer));
		
		// cache for next time (this config won't change, so its ok to cache)
		permuted_scenario_sets.put(layer, this_scenario_sets);
		
		return this_scenario_sets;
	}
		
	public List<ScenarioSet> getScenarioSets(EScenarioSetPermutationLayer layer) {
		return getScenarioSets(null, layer);
	}
	
	protected List<ScenarioSet> permuteScenarioSets(ConsoleManager cm, EScenarioSetPermutationLayer layer) {
		List<ScenarioSet> this_scenario_sets;
		if (this.scenario_sets!=null && this.scenario_sets.size() > 0) {
			this_scenario_sets = new ArrayList<ScenarioSet>(this.scenario_sets.size()+2);
			this_scenario_sets.addAll(this.scenario_sets);
		} else {
			this_scenario_sets = new LinkedList<ScenarioSet>();
		}
		
		// permute the given individual scenarios and add them to the list of scenario sets
		for (ScenarioSet scenario_set : ScenarioSet.permuteScenarioSets(layer, scenarios) ) {
			if (!this_scenario_sets.contains(scenario_set))
				this_scenario_sets.add(scenario_set);
		}
		// make sure all scenario sets have a filesystem and SAPI, code-cache, etc...
		for (ScenarioSet scenario_set : this_scenario_sets )
			Scenario.ensureContainsCriticalScenarios(scenario_set);
		//
		
		if (cm != null && this_scenario_sets.size()>0) {
			String ss_str = "";
			for ( ScenarioSet scenario_set : this_scenario_sets )
				ss_str += scenario_set.getName() + ", ";
			cm.println(EPrintType.CLUE, Config.class, "Loaded "+this_scenario_sets.size()+" Scenario-Sets: "+ss_str);
			
		}
		
		return this_scenario_sets;
	} // end protected List<ScenarioSet> permuateScenarioSets
	
	public boolean configureSMTP(SMTPProtocol smtp) {
		return configureSMTP(null, smtp);
	}
	
	public boolean configureSMTP(ConsoleManager cm, SMTPProtocol smtp) {
		ArrayList<MethodImpl> methods = by_method_name.get(CONFIGURE_SMTP_METHOD);
		if (methods==null)
			return false;
		MethodImpl m = methods.get(0);
		return null != invokeMethod(null, cm, m.go, CONFIGURE_SMTP_METHOD, smtp, m.filename);
	}
	
	public boolean configureFTPClient(FTPClient ftp) {
		return configureFTPClient(null, ftp);
	}
	
	public boolean configureFTPClient(ConsoleManager cm, FTPClient ftp) {
		ArrayList<MethodImpl> methods = by_method_name.get(CONFIGURE_FTP_CLIENT_METHOD);
		if (methods==null)
			return false;
		MethodImpl m = methods.get(0);
		return null != invokeMethod(null, cm, m.go, CONFIGURE_FTP_CLIENT_METHOD, ftp, m.filename);
	}
	
	public List<UITestPack> getUITestPacks(ConsoleManager cm) {
		ArrayList<MethodImpl> methods = by_method_name.get(GET_UI_TEST_PACK_METHOD);
		if (methods==null)
			return new ArrayList<UITestPack>(0);
		ArrayList<UITestPack> out = new ArrayList<UITestPack>(methods.size());
		for ( MethodImpl m : methods ) {
			out.add((UITestPack) invokeMethod(UITestPack.class, cm, m.go, GET_UI_TEST_PACK_METHOD, null, m.filename));
		}
		return out;
	}
	
	/**
	 * 
	 * still need to call #open on the returned PhpUnitSourceTestPack
	 * @param cm
	 * @return
	 */
	public List<PhpUnitSourceTestPack> getPhpUnitSourceTestPacks(ConsoleManager cm) {
		ArrayList<MethodImpl> methods = by_method_name.get(GET_PHP_UNIT_SOURCE_TEST_PACK_METHOD);
		if (methods==null)
			return new ArrayList<PhpUnitSourceTestPack>(0);
		ArrayList<PhpUnitSourceTestPack> out = new ArrayList<PhpUnitSourceTestPack>(methods.size());
		for ( MethodImpl m : methods ) {
			out.add((PhpUnitSourceTestPack) invokeMethod(PhpUnitSourceTestPack.class, cm, m.go, GET_PHP_UNIT_SOURCE_TEST_PACK_METHOD, null, m.filename));
		}
		return out;
	}
	
	protected Object invokeMethod(Class<?> ret_clazz, ConsoleManager cm, GroovyObject go, String method_name, Object params, Object a) {
		try {
			Object obj = go.invokeMethod(method_name, params);
			if (ret_clazz==null)
				return obj == null ? Boolean.TRUE : obj;
			else if (ret_clazz.isAssignableFrom(obj.getClass()))
				return obj;
		} catch ( Exception ex ) {
			if (cm==null)
				ex.printStackTrace(System.err);
			else
				cm.addGlobalException(EPrintType.CANT_CONTINUE, getClass(), method_name, ex, "", a);
		}
		return null;
	}
	
	public static Config loadConfigFromStreams(ConsoleManager cm, InputStream... ins) throws CompilationFailedException, InstantiationException, IllegalAccessException, IOException {
		GroovyClassLoader loader = new GroovyClassLoader(Config.class.getClassLoader());
		
		Config config = new Config();
		
		// don't load default scenarios. configuration file(s) completely replace them (not merged)
		LinkedList<Scenario> scenarios = new LinkedList<Scenario>();
		
		// load each configuration streams
		GroovyObject go;
		Class<?> clazz;
		int i=1;
		for (InputStream in : ins) {
			clazz = loader.parseClass(importString(null, IOUtil.toString(in, IOUtil.QUARTER_MEGABYTE)));
			
			go = (GroovyObject) clazz.newInstance();
			
			// call methods in file to get configuration (hosts, etc...)
			loadObjectToConfig(cm, config, go, scenarios, "InputStream #"+(i++)+" ("+in+")");
		}
		
		return loadConfigCommon(cm, scenarios, config);
	} // end public static Config loadConfigFromStreams
	
	/** 
	 * 
	 * allow flexibility in the configuration file name
		1. add .groovy for user
		2. search current dir / assume filename is absolute path
		3. search $PFTT_DIR/conf
		4. search $PFTT_DIR/conf/ini
		5. search $PFTT_DIR/conf/env
		6. search $PFTT_DIR/conf/internal
		7. search $PFTT_DIR/conf/internal_Example
		8. search $PFTT_DIR/conf/not
		9. search $PFTT_DIR/conf/app
		10. search $PFTT_DIR/conf/web_browser
		11. search $PFTT_DIR/conf/dev
		12. search $PFTT_DIR/conf/task
	 * 
	 * @param cm
	 * @param file_names
	 * @return
	 * @throws CompilationFailedException
	 * @throws InstantiationException
	 * @throws IllegalAccessException
	 * @throws IOException
	 */
	private static final String[] DIR_IN_ORDER = new String[]{"", "ini", "env", "internal", "internal_example", "not", "app", "web_browser", "task"};
	public static Config loadConfigFromFiles(ConsoleManager cm, String... file_names) throws CompilationFailedException, InstantiationException, IllegalAccessException, IOException {
		ArrayList<File> config_files = new ArrayList<File>(file_names.length);
		File config_file;
		for ( String file_name : file_names ) {
			config_file = new File(file_name);
			
			if (config_file.exists()) {
				if (!config_files.contains(config_file))
					config_files.add(config_file);
			} else {
				config_file = new File(file_name+".groovy");
				if (config_file.exists()) {
					if (!config_files.contains(config_file))
						config_files.add(config_file);
				} else {
					boolean match = false;
					for ( String dir : DIR_IN_ORDER ) {
						config_file = new File(LocalHost.getLocalPfttDir()+"/conf/"+dir+"/"+file_name);
						if (config_file.exists()) {
							if (!config_files.contains(config_file))
								config_files.add(config_file);
						} else {
							config_file = new File(LocalHost.getLocalPfttDir()+"/conf/"+dir+"/"+file_name+".groovy");
							if (config_file.exists()) {
								if (!config_files.contains(config_file))
									config_files.add(config_file);
								match = true;
								break;
							} else {
								match = false;
							}
						}
					}
					if (!match) {
						for ( String dir : DIR_IN_ORDER ) {
							config_file = new File(LocalHost.getLocalPfttDir()+"/conf/dev/"+dir+"/"+file_name);
							if (config_file.exists()) {
								if (!config_files.contains(config_file))
									config_files.add(config_file);
							} else {
								config_file = new File(LocalHost.getLocalPfttDir()+"/conf/dev/"+dir+"/"+file_name+".groovy");
								if (config_file.exists()) {
									if (!config_files.contains(config_file))
										config_files.add(config_file);
									break;
								} else {
									cm.println(EPrintType.WARNING, Config.class, "Unable to find config file: "+file_name);
									System.exit(0);
									return null;
								}
							}
						}
					}
				}
			}
		} // end for
		return loadConfigFromFiles(cm, (File[])config_files.toArray(new File[config_files.size()]));
	} // end public static Config loadConfigFromFiles
	
	/**
	 * 
	 * @param file
	 * @return
	 * @throws CompilationFailedException
	 * @throws FileNotFoundException
	 * @throws IOException
	 * @throws InstantiationException
	 * @throws IllegalAccessException
	 */
	public static String getConfigDescription(File file) throws CompilationFailedException, FileNotFoundException, IOException, InstantiationException, IllegalAccessException {
		GroovyClassLoader loader = new GroovyClassLoader(Config.class.getClassLoader());
		
		Class<?> clazz = loader.parseClass(importString(file.getAbsolutePath(), IOUtil.toString(new FileInputStream(file), IOUtil.QUARTER_MEGABYTE)), file.getAbsolutePath());
		
		GroovyObject go = (GroovyObject) clazz.newInstance();
		
		return StringUtil.toString(go.invokeMethod(DESCRIBE_METHOD_NAME, null));
	}
	
	/** 
	 * 
	 * @param cm
	 * @param files
	 * @return
	 * @throws CompilationFailedException
	 * @throws InstantiationException
	 * @throws IllegalAccessException
	 * @throws IOException
	 */
	public static Config loadConfigFromFiles(ConsoleManager cm, File... files) throws CompilationFailedException, InstantiationException, IllegalAccessException, IOException {
		GroovyClassLoader loader = new GroovyClassLoader(Config.class.getClassLoader());
		
		/*ImportCustomizer ic = new ImportCustomizer();
		ic.addImports("");
		
		CompilerConfiguration cc;
		cc.addCompilationCustomizers(ic);
		*/
		
		Config config = new Config();
		
		// don't load default scenarios. configuration file(s) completely replace them (not merged)
		LinkedList<Scenario> scenarios = new LinkedList<Scenario>();
		
		// load each configuration file
		GroovyObject go;
		Class<?> clazz;
		for (File file : files) {
			clazz = loader.parseClass(importString(file.getAbsolutePath(), IOUtil.toString(new FileInputStream(file), IOUtil.QUARTER_MEGABYTE)), file.getAbsolutePath());
			
			go = (GroovyObject) clazz.newInstance();
			
			// call methods in file to get configuration (hosts, etc...)
			loadObjectToConfig(cm, config, go, scenarios, file.getAbsolutePath());
		}
		
		return loadConfigCommon(cm, scenarios, config);
	} // end public static Config loadConfigFromFiles
	
	protected static String importString(String filename, String code) {
		// a hack to import common classes for configuration files (XXX do this a better way)
		StringBuilder sb = new StringBuilder(128+code.length());
		// import all standard Scenarios and Host types
		sb.append("import ");sb.append(UITestPack.class.getPackage().getName());sb.append(".*;\n");
		sb.append("import ");sb.append(PhpUnitSourceTestPack.class.getPackage().getName());sb.append(".*;\n");
		sb.append("import ");sb.append(PhptTestCase.class.getPackage().getName());sb.append(".*;\n");
		sb.append("import ");sb.append(JoomlaScenario.class.getPackage().getName());sb.append(".*;\n");
		sb.append("import ");sb.append(Scenario.class.getPackage().getName());sb.append(".*;\n");
		sb.append("import ");sb.append(AHost.class.getPackage().getName());sb.append(".*;\n");
		sb.append("import ");sb.append(SMTPProtocol.class.getName());sb.append(";\n");
		sb.append("import ");sb.append(FTPClient.class.getName());sb.append(";\n");
		sb.append("import ");sb.append(StringUtil.class.getName());sb.append(";\n");
		sb.append("import ");sb.append(ConsoleManager.class.getPackage().getName());sb.append(".*;\n");
		sb.append("import ");sb.append(EPrintType.class.getName());sb.append(";\n");
		sb.append("import groovy.transform.Field;\n");
		if (filename!=null) {
			sb.append("@Field String __FILE__=\""+AHost.toUnixPath(filename)+"\";");
			sb.append("@Field String __DIR__=\""+AHost.toUnixPath(AHost.dirname(filename))+"\";");
		}
		sb.append(code);
		return sb.toString();
	}
	
	protected static Config loadConfigCommon(ConsoleManager cm, LinkedList<Scenario> scenarios, Config config) {
		config.scenarios.addAll(scenarios);
		
		// scenario set permutation depends on what the scenarios are being used for (php applications, phpt core testing, etc...)
		// therefore, they can't be permuted here... wait until #getScenarioSets called
		// @see #permuteScenarioSets
		
		if (cm!=null) {
			// report progress
			if (config.hosts.size()>0) {
				cm.println(EPrintType.CLUE, Config.class, "Loaded "+config.hosts.size()+" hosts");
			}
		}
		
		
		return config;
	} // end protected static Config loadConfigCommon
	
	protected static void loadObjectToConfig(ConsoleManager cm, Config config, GroovyObject go, List<Scenario> scenarios, String file_name) {
		Object ret;
		try {
			ret = go.invokeMethod(HOSTS_METHOD, null);
			if (ret instanceof AHost) {
				config.hosts.add((AHost)ret);
			} else if (ret instanceof List) {
				// this catches HostGroup too
				for (Object o : (List<?>)ret) {
					if (o instanceof AHost) {
						if (!config.hosts.contains(o))
							config.hosts.add((AHost)o);
					} else {
						cm.println(EPrintType.OPERATION_FAILED_CONTINUING, "Config", "List returned by hosts() must only contain Host objects, not: "+o.getClass()+" see: "+file_name);
					}
				}
			} else {
				cm.println(EPrintType.OPERATION_FAILED_CONTINUING, "Config", "hosts() must return List of Hosts, not: "+(ret==null?"null":ret.getClass())+" see: "+file_name);
			}
		} catch ( MissingPropertyExceptionNoStack ex ) {
		} catch ( MissingMethodExceptionNoStack ex ) {
		} catch ( Exception ex ) {
			if (cm==null) {
				System.err.println("file_name="+file_name);
			 	ex.printStackTrace(System.err);
			} else {
				cm.addGlobalException(EPrintType.CANT_CONTINUE, Config.class, "loadObjectToConfig", ex, HOSTS_METHOD, file_name);
			}
		}
		try {
			ret = go.invokeMethod(SCENARIO_SETS_METHOD, null);
			if (ret instanceof ScenarioSet) {
				config.scenario_sets.add((ScenarioSet)ret);
			} else if (ret instanceof List) {
				for (Object o : (List<?>)ret) {
					if (o instanceof ScenarioSet) {
						if (!config.scenario_sets.contains(o))
							config.scenario_sets.add((ScenarioSet)o);
					} else {
						cm.println(EPrintType.OPERATION_FAILED_CONTINUING, "Config", "List returned by scenario_sets() must only contain ScenarioSet objects, not: "+o.getClass()+" see: "+file_name);
					}
				}
			} else {
				cm.println(EPrintType.OPERATION_FAILED_CONTINUING, "Config", "scenario_sets() must return List of ScenarioSets, not: "+(ret==null?"null":ret.getClass())+" see: "+file_name);
			}
		} catch ( MissingPropertyExceptionNoStack ex ) {
		} catch ( MissingMethodExceptionNoStack ex ) {
		} catch ( Exception ex ) {
			if (cm==null) {
				System.err.println("file_name="+file_name);
			 	ex.printStackTrace(System.err);
			} else {
				cm.addGlobalException(EPrintType.CANT_CONTINUE, Config.class, "loadObjectToConfig", ex, SCENARIO_SETS_METHOD, file_name);
			}
		}
		try {
			ret = go.invokeMethod(SCENARIOS_METHOD, null);
			if (ret instanceof Scenario) {
				scenarios.add((Scenario)ret);
			} else if (ret instanceof List) {
				for (Object o : (List<?>)ret) {
					if (o instanceof Scenario) {
						if (!scenarios.contains(o))
							scenarios.add((Scenario)o);
					} else {
						cm.println(EPrintType.OPERATION_FAILED_CONTINUING, "Config", "List returned by scenarios() must only contain Scenario objects, not: "+o.getClass()+" see: "+file_name);
					}
				}
			} else {
				cm.println(EPrintType.OPERATION_FAILED_CONTINUING, "Config", "scenarios() must return List of Scenarios, not: "+(ret==null?"null":ret.getClass())+" see: "+file_name);
			}
		} catch ( MissingPropertyExceptionNoStack ex ) {
		} catch ( MissingMethodExceptionNoStack ex ) {
		} catch ( Exception ex ) {
			if (cm==null) {
				System.err.println("file_name="+file_name);
			 	ex.printStackTrace(System.err);
			} else {
				cm.addGlobalException(EPrintType.CANT_CONTINUE, Config.class, "loadObjectToConfig", ex, SCENARIOS_METHOD, file_name);
			}
		}
		
		registerSingleMethod(cm, config, go, CONFIGURE_SMTP_METHOD, file_name);
		registerSingleMethod(cm, config, go, CONFIGURE_FTP_CLIENT_METHOD, file_name);
		registerMethod(cm, config, go, GET_PHP_UNIT_SOURCE_TEST_PACK_METHOD, file_name);
		registerMethod(cm, config, go, GET_UI_TEST_PACK_METHOD, file_name);
		registerMethod(cm, config, go, PROCESS_CONSOLE_OPTIONS_METHOD_NAME, file_name);
		registerMethod(cm, config, go, NOT_SCENARIOS_METHOD_NAME, file_name);
		registerMethod(cm, config, go, PREPARE_ENV_METHOD_NAME, file_name);
		registerMethod(cm, config, go, PREPARE_INI_METHOD_NAME, file_name);
		registerMethod(cm, config, go, PROCESS_PHPT_METHOD_NAME, file_name);
		registerMethod(cm, config, go, PROCESS_PHPUNIT_METHOD_NAME, file_name);
		registerMethod(cm, config, go, PROCESS_PHPT_TEST_PACK_METHOD_NAME, file_name);
		registerMethod(cm, config, go, PROCESS_PHPUNIT_TEST_PACK_METHOD_NAME, file_name);
	} // end protected static void loadObjectToConfig
	
	protected static void addMethod(Config config, String method_name, GroovyObject go, String file_name) {
		ArrayList<MethodImpl> methods = config.by_method_name.get(method_name);
		if (methods==null) {
			methods = new ArrayList<MethodImpl>(2);
			config.by_method_name.put(method_name, methods);
		}
		methods.add(new MethodImpl(file_name, go));
	}
	
	protected static void registerSingleMethod(ConsoleManager cm, Config config, GroovyObject go, String method_name, String file_name) {
		try {
			if (hasMethod(go, method_name)) {
				ArrayList<MethodImpl> list = config.by_method_name.get(method_name);
				if (list!=null) {
					MethodImpl m = list.get(0);
					cm.println(EPrintType.OPERATION_FAILED_CONTINUING, "Config", method_name+"("+m.filename+") overriden by : "+file_name);
				}
				addMethod(config, method_name, go, file_name);
			}
		} catch ( MissingPropertyExceptionNoStack ex ) {
		} catch ( MissingMethodExceptionNoStack ex ) {
		} catch ( Exception ex ) {
			if (cm==null) {
				System.err.println("file_name="+file_name);
			 	ex.printStackTrace(System.err);
			} else {
				cm.addGlobalException(EPrintType.CANT_CONTINUE, Config.class, "loadObjectToConfig", ex, method_name, file_name);
			}
		}
	}
	
	protected static void registerMethod(ConsoleManager cm, Config config, GroovyObject go, String method_name, String file_name) {
		try {
			if (hasMethod(go, method_name))
				addMethod(config, method_name, go, file_name);
		} catch ( MissingPropertyExceptionNoStack ex ) {
		} catch ( MissingMethodExceptionNoStack ex ) {
		} catch ( Exception ex ) {
			if (cm==null) {
				System.err.println("file_name="+file_name);
			 	ex.printStackTrace(System.err);
			} else {
				cm.addGlobalException(EPrintType.CANT_CONTINUE, Config.class, "loadObjectToConfig", ex, method_name, file_name);
			}
		}
	}
	
	protected static boolean hasMethod(GroovyObject go, String method_name) {
		for ( MetaMethod mm : go.getMetaClass().getMethods() ) {
			if ( mm.getName().equals(method_name) )
				return true;
		}
		return false;
	}
	
} // end public final class ConfigUtil
