package edu.jhu.hlt.util.simpleaccumulo;

import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.security.tokens.AuthenticationToken;
import org.apache.hadoop.io.Text;
import org.apache.thrift.TDeserializer;
import org.apache.thrift.TException;
import org.apache.thrift.TSerializer;
import org.apache.thrift.protocol.TCompactProtocol;

import edu.jhu.hlt.concrete.services.ServiceInfo;

/**
 * Super-class for this module's server implementations.
 *
 * @author travis
 */
public class SimpleAccumulo {

  // Rows are communication ids, column family is a user provided namespace,
  // and this column qualifier is the address of the comm bytes.
  public static final Text COMM_COL_QUALIFIER = new Text("comm_bytes");

  // How (Communication) values are stored
  public static final TCompactProtocol.Factory COMM_SERIALIZATION_PROTOCOL = new TCompactProtocol.Factory();

  // Configuration
  protected ServiceInfo about;
  protected SimpleAccumuloConfig config;
  
  // State
  protected Connector conn;
  
  protected TSerializer commSer;
  protected TDeserializer commDeser;
  
  public SimpleAccumulo(SimpleAccumuloConfig config) {
    this.config = config;
    this.about = new ServiceInfo()
        .setName(this.getClass().getName())
        .setDescription("a minimal accumulo-backed concrete-services implementation")
        .setVersion("0.1");
    this.commSer = new TSerializer(COMM_SERIALIZATION_PROTOCOL);
    this.commDeser = new TDeserializer(COMM_SERIALIZATION_PROTOCOL);
  }

  /**
   * You must call this before any reading/writing method.
   *
   * @param username e.g. "reader"
   * @param password e.g. new PasswordToken("an accumulo reader")
   */
  public void connect(String username, AuthenticationToken password) throws Exception {
    conn = config.connect(username, password);  
  }

  public ServiceInfo about() throws TException {
    return about;
  }

  public boolean alive() throws TException {
    return true;
  }
}
