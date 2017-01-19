package edu.jhu.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.thrift.TDeserializer;
import org.apache.thrift.TException;
import org.apache.thrift.TSerializer;
import org.apache.thrift.protocol.TCompactProtocol;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.access.FetchCommunicationService;
import edu.jhu.hlt.concrete.access.FetchRequest;
import edu.jhu.hlt.concrete.access.FetchResult;
import edu.jhu.hlt.concrete.services.NotImplementedException;
import edu.jhu.hlt.concrete.services.ServiceInfo;
import edu.jhu.hlt.concrete.services.ServicesException;
import edu.jhu.hlt.ikbp.tac.AccumuloIndex;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiTimer;
import edu.jhu.hlt.tutils.MultiTimer.TB;

/**
 * An implementation of fetch which given
 * 1) a directory to serve cached comms stored in files out of
 * 2) a "fail-over" fetch implementation to get comms not in the cache
 * will try to use the former and fall back on the latter.
 *
 * @author travis
 */
public class DiskBackedFetchWrapper implements FetchCommunicationService.Iface, AutoCloseable {
  
  public static final TDeserializer DESER = new TDeserializer(new TCompactProtocol.Factory());
  public static final TSerializer SER = new TSerializer(new TCompactProtocol.Factory());
  
  private FetchCommunicationService.Iface failOver;
  private AutoCloseable failOverResources;
  private File cacheDir;
  private boolean saveFetchedComms;
  private boolean compression;
  
  public boolean disableCache = false;
  public boolean debug = false;

  public DiskBackedFetchWrapper(
      FetchCommunicationService.Iface failOver,
      AutoCloseable failOverResources,
      File cacheDir,
      boolean saveFetchedComms,
      boolean compressionForSavedComms) {
    this.failOver = failOver;
    this.cacheDir = cacheDir;
    this.saveFetchedComms = saveFetchedComms;
    this.compression = compressionForSavedComms;
  }
  
  public FetchCommunicationService.Iface getFailover() {
    return failOver;
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
  
  public static FetchRequest fetchRequest(String... commIds) {
    FetchRequest fr = new FetchRequest();
    for (String id : commIds)
      fr.addToCommunicationIds(id);
    return fr;
  }

  public Communication fetch(String commId) {
    try {
      List<Communication> comms = fetch(new String[] {commId});
      if (comms.isEmpty())
        return null;
      return comms.get(0);
    } catch (Exception e) {
      e.printStackTrace();
      return null;
    }
  }
  
  public List<Communication> fetch(String... commIds) throws Exception {
    FetchResult r = fetch(fetchRequest(commIds));
    return r.getCommunications();
  }
  
  public static byte[] readBytes(InputStream is) throws IOException {
    int read = 0;
    int bs = 4096;
    byte[] buf = new byte[4 * bs];
    while (true) {
      if (read + bs >= buf.length)
        buf = Arrays.copyOf(buf, (int) (1.6 * buf.length + 1));
      int r = is.read(buf, read, bs);
      if (r <= 0)
        break;
      read += r;
    }
    return Arrays.copyOfRange(buf, 0, read);
  }
  
  public static MultiTimer timer() {
    return AccumuloIndex.TIMER;
  }

  @Override
  public FetchResult fetch(FetchRequest arg0) throws ServicesException, TException {
    try (TB tb1 = timer().new TB("fetch/diskbacked")) {

      // Holds the communications in this request
      Map<String, Communication> values = new HashMap<>();

      // Collect the documents available on disk
      List<String> missing = new ArrayList<>();
      for (String id : arg0.getCommunicationIds()) {
        File f = getCacheFor(id);
        if (f.isFile() && !disableCache) {
          if (debug)
            Log.info("deserializing from in " + f.getPath());
          try (TB tb = timer().new TB("fetch/diskbacked/deser")) {
            try (InputStream is = compression ? new GZIPInputStream(new FileInputStream(f)) : new FileInputStream(f)) {
              Communication c = new Communication();
              byte[] bytes = readBytes(is);
              DESER.deserialize(c, bytes);
              Object old = values.put(id, c);
              assert old == null;
            } catch (Exception e) {
              Log.info("WARNING: error while reading " + f.getPath());
              e.printStackTrace();
//              throw new RuntimeException("error while reading " + f.getPath(), e);
            }
          }
          continue;
        }
        if (debug) {
          if (disableCache && f.isFile())
            Log.info("fail over for " + id + " b/c disableCache=true");
          else
            Log.info("fail over for " + id);
        }
        missing.add(id);
      }

      // Fetch those that aren't
      if (!missing.isEmpty()) {
        FetchRequest fr = new FetchRequest();
        fr.setCommunicationIds(missing);
        try {
          FetchResult r = null;
          try (TB tb = timer().new TB("fetch/diskbacked/failOverFetch")) {
            r = failOver.fetch(fr);
          }
          for (Communication c : r.getCommunications()) {
            Object old = values.put(c.getId(), c);
            assert old == null;

            if (debug)
              Log.info("retrieved " + c.getId());

            // Optionally save the communications back to cache
            if (saveFetchedComms && !disableCache) {
              try (TB tbc = timer().new TB("fetch/diskbacked/ser")) {
                File f = getCacheFor(c.getId());
                if (debug)
                  Log.info("saving to " + f.getPath());
                assert !f.isFile();
                byte[] bytes = SER.serialize(c);
                try (OutputStream os = compression ? new GZIPOutputStream(new FileOutputStream(f)) : new FileOutputStream(f)) {
                  os.write(bytes);
                } catch (Exception e) {
                  e.printStackTrace();
                }
              }
            }
          }
        } catch (Exception e) {
          System.out.println("failOver failed! " + e.getMessage());
        }
      }

      if (values.size() < arg0.getCommunicationIdsSize())
        System.err.println("only found " + values.size() + " of " + arg0.getCommunicationIdsSize() + " comms");

//      if (debug) {
//        for (String e : values.keySet()) {
//          Communication c = values.get(e);
//          Log.info("value[" + e + "]=" + (c == null ? "null" : c.getId()));
//        }
//      }

      // Wrap up results
      FetchResult r = new FetchResult();
      r.setCommunications(new ArrayList<>());
      for (String id : arg0.getCommunicationIds()) {
        Communication c = values.get(id);
        if (c != null)
          r.addToCommunications(c);
      }
      return r;
    }
  }

  @Override
  public long getCommunicationCount() throws NotImplementedException, TException {
    throw new NotImplementedException();
  }

  @Override
  public List<String> getCommunicationIDs(long arg0, long arg1) throws NotImplementedException, TException {
    throw new NotImplementedException();
  }

  @Override
  public void close() throws Exception {
    if (failOverResources != null)
      failOverResources.close();
  }

}
