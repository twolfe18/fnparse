package edu.jhu.util;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.thrift.TDeserializer;
import org.apache.thrift.TException;
import org.apache.thrift.TSerializer;
import org.apache.thrift.protocol.TCompactProtocol;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.access.FetchCommunicationService;
import edu.jhu.hlt.concrete.access.FetchRequest;
import edu.jhu.hlt.concrete.access.FetchResult;
import edu.jhu.hlt.concrete.services.ServiceInfo;
import edu.jhu.hlt.concrete.services.ServicesException;

/**
 * An implementation of fetch which given
 * 1) a directory to serve cached comms stored in files out of
 * 2) a "fail-over" fetch implementation to get comms not in the cache
 * will try to use the former and fall back on the latter.
 *
 * @author travis
 */
public class DiskBackedFetchWrapper implements FetchCommunicationService.Iface {
  
  public static final TDeserializer DESER = new TDeserializer(new TCompactProtocol.Factory());
  public static final TSerializer SER = new TSerializer(new TCompactProtocol.Factory());
  
  private FetchCommunicationService.Iface failOver;
  private File cacheDir;
  private boolean saveFetchedComms;
  private boolean compression;

  public DiskBackedFetchWrapper(FetchCommunicationService.Iface failOver, File cacheDir, boolean saveFetchedComms, boolean compressionForSavedComms) {
    this.failOver = failOver;
    this.cacheDir = cacheDir;
    this.saveFetchedComms = saveFetchedComms;
    this.compression = compressionForSavedComms;
  }

  @Override
  public ServiceInfo about() throws TException {
    return null;
  }

  @Override
  public boolean alive() throws TException {
    return true;
  }
  
  public File getCacheFor(String commId) {
    String suf = compression ? ".comm.gz" : ".comm";
    return new File(cacheDir, commId + suf);
  }

  @Override
  public FetchResult fetch(FetchRequest arg0) throws ServicesException, TException {
    
    // Holds the communications in this request
    Map<String, Communication> values = new HashMap<>();
    
    // Collect the documents available on disk
    List<String> missing = new ArrayList<>();
    for (String id : arg0.getCommunicationIds()) {
      File f= getCacheFor(id);
      if (f.isFile()) {
        try {
          Communication c = new Communication();
//          byte[] bytes = Files.toByteArray(f);
          byte[] bytes = Files.readAllBytes(f.toPath());
          DESER.deserialize(c, bytes);
          Object old = values.put(id, c);
          assert old == null;
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      } else {
        missing.add(id);
      }
    }
    
    // Fetch those that aren't
    if (missing.isEmpty()) {
      FetchRequest fr = new FetchRequest();
      fr.setCommunicationIds(missing);
      FetchResult r = failOver.fetch(fr);
      for (Communication c : r.getCommunications()) {
        Object old = values.put(c.getId(), c);
        assert old != null;
        
        // Optionally save the communications back to cache
        if (saveFetchedComms) {
          File f = getCacheFor(c.getId());
          assert !f.isFile();
          byte[] bytes = SER.serialize(c);
          try {
            Files.write(f.toPath(), bytes);
          } catch (Exception e) {
            e.printStackTrace();
          }
        }
      }
    }
    
    if (values.size() < arg0.getCommunicationIdsSize())
      System.err.println("only found " + values.size() + " of " + arg0.getCommunicationIdsSize() + " comms");
    
    // Wrap up results
    FetchResult r = new FetchResult();
    r.setCommunications(new ArrayList<>());
    for (String id : arg0.getCommunicationIds())
      r.addToCommunications(values.get(id));
    return r;
  }

}
