package com.mostc.pftt.model.sapi;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.concurrent.ThreadSafe;

import com.mostc.pftt.host.Host;
import com.mostc.pftt.model.phpt.EPhptTestStatus;
import com.mostc.pftt.model.phpt.PhpBuild;
import com.mostc.pftt.model.phpt.PhpIni;
import com.mostc.pftt.model.phpt.PhptTestCase;
import com.mostc.pftt.results.ConsoleManager;
import com.mostc.pftt.results.PhptResultPackWriter;
import com.mostc.pftt.results.PhptTestResult;
import com.mostc.pftt.scenario.ScenarioSet;
import com.mostc.pftt.util.DownloadUtil;
import com.mostc.pftt.util.StringUtil;
import com.mostc.pftt.util.VisualStudioUtil;

/** manages and monitors Apache HTTPD web server
 * 
 * @author Matt Ficken
 *
 */

// TODO check that apache's version of OpenSSL == PHP's version of OpenSSL
@ThreadSafe
public class ApacheManager extends AbstractManagedProcessesWebServerManager {
	/** URL to ApacheLounge's Windows Builds (as a .ZIP file) */
	// NOTE: use this apachelounge 2.4.3 build b/c it uses the same openssl version as PHP
	public static final String APACHE_2_4_WINDOWS_ZIP_URL = "http://www.apachelounge.com/download/win32/binaries/httpd-2.4.3-win32-ssl_0.9.8.zip";
	public static final String APACHE_2_2_WINDOWS_ZIP_URL = "http://www.apachelounge.com/download/win32/binaries/httpd-2.2.3-win32.zip";
	//
	protected final EApacheVersion _apache_version; 
	
	public ApacheManager(EApacheVersion _apache_version) {
		this._apache_version = _apache_version;
	}
	
	public ApacheManager() {
		this(EApacheVersion.DEFAULT);
	}
	
	public static String httpd(EApacheVersion apache_version, Host host) {
		if (host.isWindows()) {
			if (apache_version==EApacheVersion.APACHE_2_2)
				return host.getSystemDrive() + "\\Apache2\\bin\\httpd.exe";
			else
				return host.getSystemDrive() + "\\Apache24\\bin\\httpd.exe";
		} else {
			return "/usr/sbin/httpd";
		}
	}
	
	protected boolean installWindows(EApacheVersion apache_version, ConsoleManager cm, Host host, PhpBuild build) throws Exception {
		// ApacheLounge build requires VC10 runtime
		if (host.dirContainsFragment(host.getSystemRoot()+"\\WinSxS", "x86_microsoft.windows.common-controls_6595b64144ccf1df_6.0.7600.16385_none_421189da2b7fabfc")) {
			// vc10rt doesn't seem to create an obvious folder here, like vc9, but SysInternals procmon found this folder
			cm.println(getClass(), "VC10 seems to be installed already");
		} else {
			if (host.execElevated(host.getPfttDir()+"/bin/vc10_vcredist_x86.exe /Q", Host.ONE_MINUTE*5).printOutputIfCrash(getClass(), cm).isSuccess())
				cm.println(getClass(), "VC10 Installed");
			else
				cm.println(getClass(), "VC10 Install was not successful, trying to continue with Apache install anyway...");
		}
		
		// download ApacheLoung build and unzip
		DownloadUtil.downloadAndUnzip(
				cm, 
				host, 
				apache_version==EApacheVersion.APACHE_2_4?APACHE_2_4_WINDOWS_ZIP_URL:APACHE_2_2_WINDOWS_ZIP_URL, 
				apache_version==EApacheVersion.APACHE_2_4?host.getSystemDrive()+"/Apache24":host.getSystemDrive()+"/Apache2"
			);
		
		// test exec'ng httpd.exe to see if its installed successfully/runnable
		return host.exec(httpd(apache_version, host)+" -V", Host.ONE_MINUTE).printOutputIfCrash(getClass(), cm).isSuccess();
	}
	
	public static boolean isSupported(PhptResultPackWriter twriter, Host host, ScenarioSet scenario_set, PhpBuild build, PhptTestCase test_case) {
		if (build.isNTS(host)) {
			twriter.getConsoleManager().println(ApacheManager.class, "Error Apache requires TS Php Build. NTS Php Builds aren't supported with Apache mod_php.");
			twriter.addResult(host, scenario_set, new PhptTestResult(host, EPhptTestStatus.XSKIP, test_case, "NTS Build not supported", null, null, null, null, null, null, null, null, null, null, null));
			
			return false;
		} else {
			return true;
		}
	}
	
	protected static EApacheVersion decideApacheVersion(ConsoleManager cm, Host host, PhpBuild build, EApacheVersion apache_version) {
		try {
			return apache_version.getApacheVersion(cm, host, build);
		} catch ( Exception ex ) {
			if (cm==null)
				ex.printStackTrace();
			else
				cm.printStackTrace(ex);
			return EApacheVersion.FALLBACK;
		}
	}
	
	private Host cache_host;
	private String cache_httpd;
	@Override
	protected ManagedProcessWebServerInstance createManagedProcessWebServerInstance(ConsoleManager cm, Host host, PhpBuild build, PhpIni ini, Map<String, String> env, String docroot, String listen_address, int port) {
		EApacheVersion apache_version = decideApacheVersion(cm, host, build, this._apache_version);
		
		String httpd = httpd(apache_version, host);
		
		String dll;
		if (host.isWindows()) {
			if (build.isNTS(host)) {
				cm.println(getName(), "Error Apache requires TS PHP Build. NTS PHP Builds aren't supported with Apache mod_php.");
				return null;
			}
			if (apache_version==EApacheVersion.APACHE_2_4)
				dll = build.getBuildPath() + "/php5apache2_4.dll";
			else
				dll = build.getBuildPath() + "/php5apache2_2.dll";
			if (!host.exists(dll)) {
				if (apache_version==EApacheVersion.APACHE_2_4)
					cm.println(getName(), "Error Apache 2.4 DLL not found with PHP Build");
				else
					cm.println(getName(), "Error Apache 2.2 DLL not found with PHP Build");
				return null;
			}
			
			
			// IMPORTANT: increase stack size or a few PCRE PHPTs will fail (default stack is large enough
			//            to run php+phpt so they pass on CLI, but too small for apache+php+phpt so they crash on apache)
			// NOTE: this returns false (no exception) if visual studio not installed
			// NOTE: this returns false (no exception) if apache binary can't be edited (already running, UAC privileges not elevated)
			if (host!=this.cache_host||this.cache_httpd==null||this.cache_httpd.equals(httpd)) {
				// do this once
				VisualStudioUtil.setExeStackSize(cm, host, httpd, VisualStudioUtil.SIXTEEN_MEGABYTES);
				this.cache_host = host;
				this.cache_httpd = httpd;
			}
		} else {
			dll = "modules/mod_php.so";
		}
		
		// create a temporary directory to hold(for each httpd.exe instance):
		//    -httpd.conf
		//    -php.ini
		//    -error.log
		final String conf_dir = host.mktempname(getClass().getSimpleName());
		try {
			host.mkdirs(conf_dir);
		} catch ( Exception ex ) {
			cm.println(getName(), "Can't create temporary dir to run Apache");
			return null;
		}
		
		// CRITICAL: must add extension dir (and fix path) AND it MUST end with \ (Windows) or / (Linux)
		if (StringUtil.isEmpty(ini.getExtensionDir()))
			ini.setExtensionDir(host.fixPath(build.getDefaultExtensionDir())+host.dirSeparator());
		else if (!ini.getExtensionDir().endsWith(host.dirSeparator()))
			// extension dir already set, but doesn't end with / or \
			ini.setExtensionDir(host.fixPath(ini.getExtensionDir()+host.dirSeparator()));
		//
		
		final String php_conf_file = host.joinIntoOnePath(conf_dir, "php.ini");
		final String apache_conf_file = host.joinIntoOnePath(conf_dir, "httpd.conf");
		final String error_log = host.joinIntoOnePath(conf_dir, "error.log");
		
		if (env==null)
			env = new HashMap<String,String>(2);
		// tell apache mod_php where to find php.ini
		env.put("PHPRC", php_conf_file);
		// these 2 env vars are needed for some phpts
		env.put("TEST_PHP_EXECUTABLE", build.getPhpExe());
		env.put("TEST_PHP_CGI_EXECUTABLE", build.getPhpCgiExe());
		
		// apache configuration (also tells where to find php.ini. see PHPIniDir directive)
		String conf_str = writeConfigurationFile(apache_version, host, dll, conf_dir, error_log, listen_address, port, docroot);
		
		try {
			host.saveTextFile(php_conf_file, ini.toString());
		} catch ( Exception ex ) {
			cm.printStackTrace(ex);
			cm.println(getName(), "Unable to save PhpIni: "+php_conf_file);
			return null;
		}
		try {
			host.saveTextFile(apache_conf_file, conf_str);
		} catch ( Exception ex ) {
			cm.printStackTrace(ex);
			cm.println(getName(), "Unable to save Apache configuration: "+apache_conf_file);
			return null;
		}
		
		// @see #createWebServerInstance for where command is executed to create httpd.exe process
		return new ApacheWebServerInstance(apache_version, this, httpd+" -X -f "+host.fixPath(apache_conf_file), ini, env, listen_address, port, host, conf_dir, apache_conf_file, error_log);
	} // end protected ManagedProcessWebServerInstance createManagedProcessWebServerInstance
	
	public class ApacheWebServerInstance extends ManagedProcessWebServerInstance {
		protected final String conf_dir, apache_conf_file, error_log;
		protected final Host host;
		protected final EApacheVersion apache_version;
		protected WeakReference<String> log_ref;
		
		public ApacheWebServerInstance(EApacheVersion apache_version, ApacheManager ws_mgr, String cmd, PhpIni ini, Map<String,String> env, String hostname, int port, Host host, String conf_dir, String apache_conf_file, String error_log) {
			super(ws_mgr, cmd, ini, env, hostname, port);
			this.apache_version = apache_version;
			this.host = host;
			this.conf_dir = conf_dir;
			this.apache_conf_file = apache_conf_file;
			this.error_log = error_log;
		}
		
		@Override
		public String getSAPIOutput() {
			if (StringUtil.isNotEmpty(error_log)) {
				// try to include server's error log
				try {
					String log = readLogCache();
					
					if (StringUtil.isNotEmpty(log))
						return super.getSAPIOutput() + "\n" + log;
				} catch ( Exception ex ) {
				}
			}
			return super.getSAPIOutput();
		}
		
		protected String readLogCache() throws IllegalStateException, IOException {
			String log = null;
			if (log_ref!=null)
				log = log_ref.get();
			if (log==null) {
				log = host.getContents(error_log);
				if (StringUtil.isNotEmpty(log)) {
					log_ref = new WeakReference<String>(log);
				}
			}
			return log;
		}
		
		@Override
		protected void do_close() {
			super.do_close();
			
			if (!isCrashed()) {
				// don't delete temp dir if crashed so user can analyze
				try {
					if (StringUtil.isEmpty(error_log)) {
						// cache log in memory before deleting on disk in case its still needed after #close call
						readLogCache();
					}
					
					host.delete(conf_dir);
				} catch ( Exception ex ) {}
			}
		}
		
		@Override
		public String getInstanceInfo(ConsoleManager cm) {
			try {
				return host.exec(httpd(apache_version, host)+" -V", Host.ONE_MINUTE).output;
			} catch ( Exception ex ) {
				cm.printStackTrace(ex);
				return StringUtil.EMPTY;
			}
		}
		
	} // end public class ApacheWebServerInstance

	@Override
	public boolean setup(ConsoleManager cm, Host host, PhpBuild build) {
		EApacheVersion apache_version = decideApacheVersion(cm, host, build, _apache_version);
		
		if (host.exists(httpd(apache_version, host)))
			// already installed
			return true;
		
		try {
			if (host.isWindows() ? installWindows(apache_version, cm, host, build) : installLinux(cm, host)) {
				cm.println(getClass(), "");
				
				return true;
			}
		} catch ( Exception ex ) {
			cm.printStackTrace(ex);
		}
		return false;
	}
	
	protected boolean installLinux(ConsoleManager cm, Host host) throws Exception {
		return host.exec("emerge www-servers/apache", Host.ONE_MINUTE * 30).printOutputIfCrash(getClass(), cm).isSuccess();
	}
	
	@Override
	public boolean allowConcurrentWebServerSAPIInstances() {
		return true;
	}

	@Override
	public boolean isSSLSupported() {
		return true;
	}
	
	@Override
	public String getName() {
		return "Apache";
	}
	
	public static String writeConfigurationFile(EApacheVersion apache_version, Host host, String php_dll_path, String conf_dir, String error_log, String listen_address, int port, String docroot) {
		// critical: path MUST NOT end with / or \
		if (docroot.endsWith("/")||docroot.endsWith("\\"))
			docroot = docroot.substring(0, docroot.length()-1);
		conf_dir = host.fixPath(conf_dir);
		if (!conf_dir.endsWith("/")&&!conf_dir.endsWith("\\"))
			conf_dir += host.dirSeparator();
		
		StringBuilder sb = new StringBuilder(400);
		sb.append("LoadModule php5_module \""+Host.toUnixPath(php_dll_path)+"\"\n"); 
		sb.append("PHPIniDir \""+conf_dir+"\"\n");
		if (apache_version==EApacheVersion.APACHE_2_4)
			sb.append("LoadModule authz_core_module modules/mod_authz_core.so\n");
		sb.append("LoadModule log_config_module modules/mod_log_config.so\n");
		sb.append("LoadModule mime_module modules/mod_mime.so\n");
		sb.append("ServerAdmin administrator@"+listen_address+"\n");
		// CRITICAL: ServerName critical on apache 2.2 (2.4?)
		sb.append("ServerName "+listen_address+":"+port+"\n");
		// ServerName fails if listen_address is IPv6, ex: [2001:0:4137:9e76:3cb8:730:3f57:feaf]:40086
		sb.append("Listen "+listen_address+":"+port+"\n");
		if (apache_version==EApacheVersion.APACHE_2_4 && host.isWindows()) {
			// may get this error (in log): "winnt_accept: Asynchronous AcceptEx failed"
			// solution: @see http://www.mydigitallife.info/winnt_accept-asynchronous-acceptex-failed-error-in-apache-log/
			// also: @see http://www.apachelounge.com/viewtopic.php?p=21369
			sb.append("AcceptFilter http none\n");
			sb.append("AcceptFilter https none\n");
			// NOTE: Win32DisableAcceptEx was removed in apache 2.4
			// NOTE: including directive on apachelounge 2.2 causes failure
			//sb.append("Win32DisableAcceptEx\n");
			sb.append("EnableMMAP off\n");
			sb.append("EnableSendfile off\n");
		}
		sb.append("<Directory />\n");
		sb.append("    AllowOverride none\n");
		//sb.append("    Require all denied\n");
		sb.append("</Directory>\n");
		sb.append("DocumentRoot \""+host.fixPath(docroot)+"\"\n");
		sb.append("<Directory \""+host.fixPath(docroot)+"\">\n");
		sb.append("    Options Indexes FollowSymLinks\n");
		sb.append("    AllowOverride None\n");
		//sb.append("    Require all granted\n");
		sb.append("</Directory>\n");
		sb.append("ErrorLog \""+host.fixPath(error_log)+"\"\n");
		sb.append("LogLevel warn\n");
		sb.append("<IfModule log_config_module>\n");
		sb.append("    LogFormat \"%h %l %u %t \\\"%r\\\" %>s %b \\\"%{Referer}i\\\" \\\"%{User-Agent}i\\\"\" combined\n");
		sb.append("    LogFormat \"%h %l %u %t \\\"%r\\\" %>s %b\\\" common\n");
		sb.append("</IfModule>\n");
		sb.append("<IfModule mime_module>\n");
		sb.append("    AddType application/x-httpd-php .php\n");
		sb.append("</IfModule>\n");
		
		return sb.toString();
	} // end public String writeConfigurationFile

	@Override
	public boolean start(ConsoleManager cm, Host host, PhpBuild build) {
		try {
			EApacheVersion apache_version = decideApacheVersion(cm, host, build, _apache_version);
			
			if (host.isWindows())
				return host.exec(httpd(apache_version, host)+" -k start", Host.ONE_MINUTE).printOutputIfCrash(getClass(), cm).isSuccess();
			else
				return host.exec("/etc/init.d/apache start", Host.ONE_MINUTE).printOutputIfCrash(getClass(), cm).isSuccess();
		} catch ( Exception ex ) {
			cm.printStackTrace(ex);
		}
		return false;
	}

	@Override
	public boolean stop(ConsoleManager cm, Host host, PhpBuild build) {
		try {
			EApacheVersion apache_version = decideApacheVersion(cm, host, build, _apache_version);
			
			if (host.isWindows())
				return host.exec(httpd(apache_version, host)+" -k stop", Host.ONE_MINUTE).printOutputIfCrash(getClass(), cm).isSuccess();
			else
				return host.exec("/etc/init.d/apache stop", Host.ONE_MINUTE).printOutputIfCrash(getClass(), cm).isSuccess();
		} catch ( Exception ex ) {
			cm.printStackTrace(ex);
		}
		return false;
	}

	@Override
	public String getDefaultDocroot(Host host, PhpBuild build) {
		EApacheVersion apache_version = decideApacheVersion(null, host, build, _apache_version);
		
		return host.isWindows() ? apache_version==EApacheVersion.APACHE_2_2 ? host.getSystemDrive() + "\\Apache2\\htdocs" : host.getSystemDrive() + "\\Apache24\\htdocs" : "/var/www/localhost/htdocs";
	}
	
} // end public class ApacheManager