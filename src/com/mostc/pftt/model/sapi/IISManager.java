package com.mostc.pftt.model.sapi;

import java.util.Map;
import javax.annotation.concurrent.ThreadSafe;

import com.github.mattficken.io.StringUtil;
import com.mostc.pftt.host.ExecOutput;
import com.mostc.pftt.host.AHost;
import com.mostc.pftt.host.Host;
import com.mostc.pftt.model.core.PhpBuild;
import com.mostc.pftt.model.core.PhpIni;
import com.mostc.pftt.results.ConsoleManager;
import com.mostc.pftt.results.EPrintType;
import com.mostc.pftt.scenario.ScenarioSet;
import com.mostc.pftt.util.ErrorUtil;

/** manages and monitors IIS and IIS express web servers
 * 
 * @author Matt Ficken
 *
 */

// XXX need process handle for each IIS process in order to tell for sure if the process crashes
// this class only has to work on windows vista+
@ThreadSafe
public class IISManager extends WebServerManager {
	/** To get a list of web applications (web apps registered with IIS): 
	 * `appcmd list app`
	 * 
	 * To get a list of web sites:
	 * `appcmd list site`
	 * 
	 */
	public static final String DEFAULT_SITE_NAME = "Default Web Site";
	public static final String DEFAULT_APP_NAME = "";
	public static final String DEFAULT_SITE_AND_APP_NAME = "Default Web Site/";
	
	protected String appcmd_path(Host host) {
		return host.getSystemRoot()+"\\System32\\inetsrv\\appcmd.exe";
	}
	
	protected ExecOutput appcmd(AHost host, String args) throws Exception {
		String cmd = appcmd_path(host)+" "+args;
		ExecOutput eo = host.execElevatedOut(cmd, AHost.ONE_MINUTE);
		//System.err.println(cmd);
		//System.err.println(eo.output);
		return eo;
	}
	
	@Override
	public boolean start(ConsoleManager cm, Host host, PhpBuild build, PhpIni ini) {
		try {
			return host.execElevated(cm, getClass(), "net start w3svc", AHost.ONE_MINUTE*2);
		} catch ( Exception ex ) {
			cm.addGlobalException(EPrintType.CANT_CONTINUE, getClass(), "start", ex, "");
			return false;
		}
	}
	
	@Override
	public boolean stop(ConsoleManager cm, Host host, PhpBuild build, PhpIni ini) {
		try {
			return host.execElevated(cm, getClass(), "net stop w3svc", AHost.ONE_MINUTE*2);
		} catch ( Exception ex ) {
			if (cm==null)
				ex.printStackTrace();
			else
				cm.addGlobalException(EPrintType.CANT_CONTINUE, getClass(), "stop", ex, "");
			return false;
		}
	}
	
	public ExecOutput configure(ConsoleManager cm, AHost host, PhpBuild build, String doc_root, PhpIni ini, Map<String,String> env, String listen_address, int listen_port) {
		return configure(cm, host, build, DEFAULT_SITE_NAME, DEFAULT_APP_NAME, doc_root, ini, env, listen_address, listen_port);
	}
	
	public ExecOutput configure(ConsoleManager cm, AHost host, PhpBuild build, String site_name, String app_name, String doc_root, PhpIni ini, Map<String,String> env, String listen_address, int listen_port) {
		// clear previous configuration from previous interrupted runs
		undoConfigure(cm, host);
		
		addToPHPIni(ini);
		
		String php_binary = build.getPhpCgiExe();
		String c_section = "section:system.webServer";
		
		// TODO env = prepareENV(env, php_conf_file, build, scenario_set, httpd);

		try {
			ExecOutput eo;
			
			// bind HTTP to listen_port
			eo = appcmd(host, "set site /site.name:"+site_name+" /+bindings.[protocol='http',bindingInformation='*:"+listen_port+":']");
			if (eo.isCrashed())
				return eo;
			// setup PHP to be run with FastCGI
			eo = appcmd(host, "set config /"+c_section+"/fastCGI /+[fullPath='"+php_binary+"',arguments='',instanceMaxRequests='10000',maxInstances='0']");
			if (eo.isCrashed())
				return eo;
			// setup important environment variables
			eo = appcmd(host, "set config /"+c_section+"/fastCGI /+[fullPath='"+php_binary+"'].environmentVariables.[name='PHPRC',value='"+build.getBuildPath()+"']");
			if (eo.isCrashed())
				return eo;
			eo = appcmd(host, "set config /"+c_section+"/fastCGI /+[fullPath='"+php_binary+"'].environmentVariables.[name='PHP_FCGI_MAX_REQUESTS',value='50000']");
			if (eo.isCrashed())
				return eo;
			// copy any environment variables that need to be passed to PHP
			//
			// PHPT database tests need this in order to run
			if (env!=null) {
				for ( String name : env.keySet() ) {
					String value = env.get(name);
					
					eo = appcmd(host, "set config /"+c_section+"/fastCGI /+[fullPath='"+php_binary+"'].environmentVariables.[name='"+name+"',value='"+value+"']");
					if (eo.isCrashed())
						return eo;
				}
			}
			//
			eo = appcmd(host, "set config /"+c_section+"/handlers /+[name='PHP_via_FastCGI',path='*.php',verb='*',modules='FastCgiModule',scriptProcessor='"+php_binary+"']");
			if (eo.isCrashed())
				return eo;
			// set docroot to the location of the installed test-pack
			return appcmd(host, "set vdir /vdir.name:\""+site_name+"/"+app_name+"\" /physicalPath:\""+doc_root+"\"");
		} catch ( Exception ex ) {
			cm.addGlobalException(EPrintType.CANT_CONTINUE, getClass(), "configure", ex, "");
		}
		return null;
	} // end public ExecOutput configure
	
	public boolean undoConfigure(ConsoleManager cm, AHost host) {
		String c_section = "section:system.webServer";
		
		try {
			return appcmd(host, "clear config /"+c_section+"/fastCGI").printOutputIfCrash(getClass(), cm).isSuccess() &&
					appcmd(host, "set config /"+c_section+"/handlers /-[name='PHP_via_FastCGI']").printOutputIfCrash(getClass(), cm).isSuccess();
		} catch ( Exception ex ) {
			if (cm==null)
				ex.printStackTrace();
			else
				cm.addGlobalException(EPrintType.CANT_CONTINUE, getClass(), "undoConfigure", ex, "");
		}
		return false;
	}
	
	public static void addToPHPIni(PhpIni ini) {
		//add these directives to php.ini file used by php.exe
		ini.putSingle("fastcgi.impersonate", 1);
		ini.putSingle("cgi.fix_path_info", 1);
		ini.putSingle("cgi.force_redirect", 0);
		ini.putSingle("cgi.rfc2616_headers", 0);
	}
	    
	@Override
	public String getName() {
		return "IIS";
	}
	
	WebServerInstance wsi;
	@Override
	public synchronized WebServerInstance getWebServerInstance(ConsoleManager cm, AHost host, ScenarioSet scenario_set, PhpBuild build, PhpIni ini, Map<String,String> env, String docroot, WebServerInstance assigned, boolean debugger_attached, Object server_name) {
		if (wsi==null)
			wsi = super.getWebServerInstance(cm, host, scenario_set, build, ini, env, docroot, assigned, debugger_attached, server_name);
		return wsi;
	}
	
	@Override
	protected WebServerInstance createWebServerInstance(ConsoleManager cm, AHost host, ScenarioSet scenario_set, PhpBuild build, PhpIni ini, Map<String,String> env, String doc_root, boolean debugger_attached, Object server_name, boolean is_replacement) {
		final String listen_address = host.getLocalhostListenAddress();
		final int listen_port = 80;
		
		ExecOutput eo = configure(cm, host, build, doc_root, ini, env, listen_address, listen_port);
		
		if (eo.isSuccess()) {
			String err_str = "";
			try {
				eo = host.execElevatedOut("net start w3svc", AHost.ONE_MINUTE*2);
				if (eo.isSuccess()) {
					return new IISWebServerInstance(this, StringUtil.EMPTY_ARRAY, ini, env, host, build, listen_address, listen_port);
				} else {
					err_str = eo.output;
				}
			} catch ( Exception ex ) {
				err_str = ErrorUtil.toString(ex);
			}
			return new CrashedWebServerInstance(this, ini, env, err_str);
		}
		return new CrashedWebServerInstance(this, ini, env, eo.output);
	}
	
	public class IISWebServerInstance extends WebServerInstance {
		protected final AHost host;
		protected final PhpBuild build;
		protected final String hostname;
		protected final int port;
		protected boolean running = true;

		public IISWebServerInstance(WebServerManager ws_mgr, String[] cmd_array, PhpIni ini, Map<String,String> env, AHost host, PhpBuild build, String hostname, int port) {
			super(ws_mgr, cmd_array, ini, env);
			this.host = host;
			this.build = build;
			this.hostname = hostname;
			this.port = port;
		}
				
		@Override
		public String toString() {
			return hostname+":"+port;
		}

		@Override
		public String getHostname() {
			return hostname;
		}

		@Override
		public int getPort() {
			return port;
		}

		@Override
		protected synchronized void do_close(ConsoleManager cm) {
			if (!running)
				return;
			
			stop(null, host, build, null);
			undoConfigure(null, host);
			running = false;
		}

		private long last_run_check;
		@Override
		public boolean isRunning() {
			// only check once every 10 seconds
			if (last_run_check + 10000 > System.currentTimeMillis()) {
				running = checkIsRunning();
				
				last_run_check = System.currentTimeMillis();
			}
			return running;
		}
		
		protected boolean checkIsRunning() {
			try {
				String out = host.execOut("TASKLIST /NH /FO CSV /FI \"SERVICES eq w3svc\"", AHost.ONE_MINUTE).output;
				return StringUtil.isNotEmpty(out) && !out.contains("No tasks");
			} catch ( Exception ex ) { 
				ex.printStackTrace();
				return false;
			}
		}

		@Override
		public String getInstanceInfo(ConsoleManager cm) {
			try {
				return appcmd(host, "-v").output;
			} catch ( Exception ex ) {
				cm.addGlobalException(EPrintType.OPERATION_FAILED_CONTINUING, getClass(), "getInstanceInfo", ex, "");
				return StringUtil.EMPTY;
			}
		}

		@Override
		public boolean isDebuggerAttached() {
			return false;
		}

		@Override
		public String getDocroot() {
			return null; // TODO
		}

		@Override
		public String getSAPIConfig() {
			return null; // TODO
		}

		@Override
		public boolean isCrashedAndDebugged() {
			return false;
		}

		@Override
		public String getNameWithVersionInfo() {
			return getName();
		}

		@Override
		public String getName() {
			return "IIS";
		}

		@Override
		public void close(ConsoleManager cm) {
		}
		
	} // end public class IISWebServerInstance

	@Override
	public boolean allowConcurrentWebServerSAPIInstances() {
		return false;
	}

	@Override
	public boolean isSSLSupported() {
		return true;
	}

	@Override
	public IISScenarioSetup setup(ConsoleManager cm, Host host, PhpBuild build) {
		if (!host.isWindows()) {
			cm.println(EPrintType.SKIP_OPERATION, IISManager.class, "Only supported OS is Windows");
			return null;
		} else if (host.isBeforeVista()) {
			cm.println(EPrintType.SKIP_OPERATION, IISManager.class, "Only Windows Vista/2008/7/2008r2/8/2012+ are supported. Upgrade Windows and try again.");
			return null;
		} else {
			if (host.exists(appcmd_path(host))) {
				cm.println(EPrintType.OPERATION_FAILED_CONTINUING, getClass(), "IIS already installed");
				
				return new IISScenarioSetup();
			}
			
			try {
				if (host.execElevated(cm, getClass(), "pkgmgr /iu:IIS-WebServerRole;IIS-WebServer;IIS-StaticContent;IIS-WebServerManagementTools;IIS-ManagementConsole;IIS-CGI", AHost.HALF_HOUR)) {
					cm.println(EPrintType.OPERATION_FAILED_CONTINUING, getClass(), "IIS installed");
					
					return new IISScenarioSetup();
				} else {
					cm.println(EPrintType.CANT_CONTINUE, getClass(), "IIS install failed");
				}
			} catch ( Exception ex ) {
				cm.addGlobalException(EPrintType.CANT_CONTINUE, getClass(), "setup", ex, "exception during IIS install.", host);
			}
			return null;
		}
	} // end public IISScenarioSetup setup

	public class IISScenarioSetup extends SimpleWebServerSetup {

		@Override
		public void close(ConsoleManager cm) {
			
		}
		
		@Override
		public String getNameWithVersionInfo() {
			return getName();
		}

		@Override
		public String getName() {
			return "IIS";
		}

		@Override
		public String getHostname() {
			return "127.0.0.1";
		}

		@Override
		public int getPort() {
			return 80;
		}

	} // end public class IISScenarioSetup

	@Override
	public String getDefaultDocroot(Host host, PhpBuild build) {
		return host.getSystemDrive() + "\\inetpub\\wwwroot";
	}

} // end public class IISManager
