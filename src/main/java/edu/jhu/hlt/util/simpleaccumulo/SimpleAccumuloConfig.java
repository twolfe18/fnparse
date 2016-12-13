package edu.jhu.hlt.util.simpleaccumulo;

import java.io.Serializable;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.Instance;
import org.apache.accumulo.core.client.ZooKeeperInstance;
import org.apache.accumulo.core.client.security.tokens.AuthenticationToken;

import edu.jhu.hlt.tutils.ExperimentProperties;

/**
 * Specifies a location to put data.
 *
 * @author travis
 */
public class SimpleAccumuloConfig implements Serializable {
  private static final long serialVersionUID = 6363692365193204723L;

  // TODO support dev/production split
  public static final String HOST_TABLE = "simple_accumulo_dev";

  public final String namespace;      // used as column family
  public final String table;          // e.g. HOST_TABLE
  public final String instanceName;   // e.g. "minigrid"
  public final String zookeepers;     // e.g. "r8n04.cm.cluster:2181,r8n05.cm.cluster:2181,r8n06.cm.cluster:2181"
  
  public SimpleAccumuloConfig(String namespace, String table, String instanceName, String zookeepers) {
    this.namespace = namespace;
    this.table = table;
    this.instanceName = instanceName;
    this.zookeepers = zookeepers;
  }

  /**
   * @param username e.g. "reader"
   * @param password e.g. new PasswordToken("an accumulo reader")
   */
  public Connector connect(String username, AuthenticationToken password) throws AccumuloException, AccumuloSecurityException {
    Instance inst = new ZooKeeperInstance(instanceName, zookeepers);
    Connector conn = inst.getConnector(username, password);
    return conn;
  }
  
  public static SimpleAccumuloConfig fromConfig(ExperimentProperties config) {
    return new SimpleAccumuloConfig(
        config.getString("accumulo.namespace"),
        HOST_TABLE,
        config.getString("accumulo.instance", "minigrid"),
        config.getString("accumulo.zookeepers", "r8n04.cm.cluster:2181,r8n05.cm.cluster:2181,r8n06.cm.cluster:2181"));
  }
}
