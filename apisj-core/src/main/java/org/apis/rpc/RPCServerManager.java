package org.apis.rpc;

import org.apis.config.SystemProperties;
import org.apis.facade.Apis;

import java.io.*;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Properties;
import java.util.TreeSet;

public class RPCServerManager {
    private static RPCServerManager sManager = null;

    private Apis apis;

    private  Properties prop = null;

    private int port;
    private String id;
    private String password;
    private boolean isAvailable;
    private int maxConnection;
    private int maxPeers;
    private String whitelist;

    private String PATH_PROPERTIES = "config/rpc.properties";

    public static final String KEY_PORT = "port";
    public static final String KEY_ID = "id";
    public static final String KEY_PASSWORD = "password";
    public static final String KEY_AVAILABLE_RPC = "use_rpc";
    public static final String KEY_ALLOW_IP = "allow_ip";
    public static final String KEY_MAX_CONNECTION = "max_connections";
    public static final String KEY_MAX_PEERS = "max_peers";

    public static final String KEY_HTTP_AVAILABLE_RPC = "http_use_rpc";
    public static final String KEY_HTTP_PORT = "http_port";
    public static final String KEY_HTTP_THREAD = "http_thread";

    private RPCWebSocketServer rpcServer = null;
    private boolean isRun = false;

    public static RPCServerManager getInstance() throws IOException {
        if(sManager == null) {
            sManager = new RPCServerManager();
        }
        return sManager;
    }

    public static RPCServerManager getInstance(Apis apis) throws IOException {
        if(sManager == null) {
            sManager = new RPCServerManager(apis);
        }
        return sManager;
    }

    private RPCServerManager() throws IOException {
        loadProperties();
    }

    private RPCServerManager(Apis apis) throws IOException {
        this.apis = apis;
        loadProperties();
    }

    public void setApis(Apis apis) {
        this.apis = apis;
    }

    public void loadProperties() throws IOException {
        prop = new Properties() {
            @Override
            public synchronized Enumeration<Object> keys() {
                return Collections.enumeration(new TreeSet<>(super.keySet()));
            }
        };

        InputStream input = new FileInputStream(SystemProperties.getDefault().configDir() + "/rpc.properties");
        prop.load(input);

        try {
            this.port = Integer.parseInt(prop.getProperty(KEY_PORT));
        } catch (NumberFormatException e) {
            this.port = 0;
        }
        this.id = prop.getProperty(KEY_ID);
        this.password = prop.getProperty(KEY_PASSWORD);
        this.isAvailable = Boolean.parseBoolean(prop.getProperty(KEY_AVAILABLE_RPC));
        this.whitelist = prop.getProperty(KEY_ALLOW_IP);
        try {
            this.maxConnection = Integer.parseInt(prop.getProperty(KEY_MAX_CONNECTION));
        } catch(NumberFormatException e) {
            this.maxConnection = -1;
        }
        try {
            this.maxPeers = Integer.parseInt(prop.getProperty(KEY_MAX_PEERS));
        } catch(NumberFormatException e) {
            this.maxPeers = 30;
        }
    }

    /**
     * RPC 설정이 변경되었다면 stopServer() 실행 후 startServer()를 실행해야한다.
     *
     * @throws IOException 설정 저장에 실패했을 경우
     */
    public void saveProperties() throws IOException {
        OutputStream output = new FileOutputStream(SystemProperties.getDefault().configDir() + "/rpc.properties");
        this.prop.store(output, null);
        output.close();
    }

    public boolean isAvailable() {
        return isAvailable;
    }

    public void setAvailable(boolean available) {
        isAvailable = available;
        this.prop.setProperty(KEY_AVAILABLE_RPC, String.valueOf(available));
    }

    public int getMaxConnection() {
        return maxConnection;
    }

    public int getMaxPeers() {
        return maxPeers;
    }

    public void setMaxConnection(int maxConnection) {
        this.maxConnection = maxConnection;
        this.prop.setProperty(KEY_MAX_CONNECTION, String.valueOf(maxConnection));
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        if(port < 0 || port > 65535) {
            return;
        }
        this.port = port;
        this.prop.setProperty(KEY_PORT, String.valueOf(port));
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        if(id == null || id.isEmpty()) {
            return;
        }
        this.id = id;
        this.prop.setProperty(KEY_ID, id);
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        if(password == null || password.isEmpty()) {
            return;
        }
        this.password = password;
        this.prop.setProperty(KEY_PASSWORD, password);
    }

    public String getWhitelist() {
        return whitelist;
    }

    /**
     * 1.1.1.1, 2.2.2.2, 3.3.3.3, xxx.xxx.xxx.xxx 등 IP 주소 목록
     * @param whitelist 쉼표로 구분된 아이피 주소 목록
     */
    public void setWhitelist(String whitelist) {
        if(whitelist == null || whitelist.isEmpty()) {
            whitelist = "127.0.0.1";
        }
        this.whitelist = whitelist;
        this.prop.setProperty(KEY_ALLOW_IP, whitelist);
    }

    public void startServer() {
        if(isRun) {
            return;
        }
        rpcServer = new RPCWebSocketServer(port, id, password, apis);
        rpcServer.setConnectionRule(whitelist, maxConnection);
        rpcServer.setReuseAddr(true);

        rpcServer.start();
        isRun = true;
    }

    public void stopServer() {
        try {
            if(rpcServer != null) {
                rpcServer.stop();
                rpcServer = null;
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
        isRun = false;
    }


    public boolean isHttpServerEnabled() {
        return Boolean.parseBoolean(prop.getProperty(KEY_HTTP_AVAILABLE_RPC));
    }

    public int getHttpPort() {
        return Integer.parseInt(prop.getProperty(KEY_HTTP_PORT));
    }

    public int getHttpThreadPoolSize() {
        return Integer.parseInt(prop.getProperty(KEY_HTTP_THREAD));
    }


    @Override
    public String toString() {
        return "RPCServerManager{" +
                "apis=" + apis +
                ", port=" + port +
                ", id='" + id + '\'' +
                ", password='" + password + '\'' +
                ", isAvailable=" + isAvailable +
                ", maxConnection=" + maxConnection +
                ", whitelist='" + whitelist + '\'' +
                ", rpcServer=" + rpcServer +
                '}';
    }
}
