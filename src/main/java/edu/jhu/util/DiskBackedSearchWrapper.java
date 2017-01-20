package edu.jhu.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.thrift.TException;

import com.google.common.hash.HashFunction;

import edu.jhu.hlt.concrete.search.SearchCapability;
import edu.jhu.hlt.concrete.search.SearchQuery;
import edu.jhu.hlt.concrete.search.SearchResult;
import edu.jhu.hlt.concrete.search.SearchService;
import edu.jhu.hlt.concrete.services.ServiceInfo;
import edu.jhu.hlt.concrete.services.ServicesException;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.hash.GuavaHashUtil;

/**
 * Same design as {@link DiskBackedFetchWrapper}.
 *
 * @author travis
 */
public class DiskBackedSearchWrapper implements SearchService.Iface, AutoCloseable {
  
  public static final HashFunction HASH = GuavaHashUtil.goodFastHash(32, 9001);

  private SearchService.Iface failOver;
  private AutoCloseable failOverResources;
  private File cacheDir;
  private boolean saveResults;
  private boolean compression;
  
  public boolean disableCache = false;
  public boolean debug = false;

  public DiskBackedSearchWrapper(
      SearchService.Iface failOver,
      AutoCloseable failOverResources,
      File cacheDir,
      boolean saveResults,
      boolean compressionForSavedResults) {
    if (!cacheDir.isDirectory())
      throw new IllegalArgumentException();
    this.failOver = failOver;
    this.cacheDir = cacheDir;
    this.saveResults = saveResults;
    this.compression = compressionForSavedResults;
  }
  
  public SearchService.Iface getFailover() {
    return failOver;
  }

  @Override
  public ServiceInfo about() throws TException {
    return failOver.about();
  }

  @Override
  public boolean alive() throws TException {
    return failOver.alive();
  }

  @Override
  public List<SearchCapability> getCapabilities() throws ServicesException, TException {
    return failOver.getCapabilities();
  }

  @Override
  public List<String> getCorpora() throws ServicesException, TException {
    return failOver.getCorpora();
  }

  public File getCacheFor(SearchQuery arg0) {
    try {
      // Compute a 32-bit hash/tag for this query
      byte[] bs = DiskBackedFetchWrapper.SER.serialize(arg0);
      int h = HASH.hashBytes(bs).asInt();
      String suf = compression ? ".searchResult.gz" : ".searchResult";
      return new File(cacheDir, Integer.toHexString(h) + suf);
    } catch (Exception e) {
      e.printStackTrace();
      return null;
    }
  }

  @Override
  public SearchResult search(SearchQuery arg0) throws ServicesException, TException {
    
    File f = getCacheFor(arg0);
    if (!disableCache && f.exists()) {
      SearchResult r = new SearchResult();
      try (InputStream is = compression ? new GZIPInputStream(new FileInputStream(f)) : new FileInputStream(f)) {
        if (debug)
          Log.info("returning from cache: " + f.getPath());
        byte[] bytes = DiskBackedFetchWrapper.readBytes(is);
        DiskBackedFetchWrapper.DESER.deserialize(r, bytes);
        return r;
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
    
    SearchResult r = failOver.search(arg0);
    
    if (saveResults) {
      try (OutputStream is = compression ? new GZIPOutputStream(new FileOutputStream(f)) : new FileOutputStream(f)) {
        if (debug)
          Log.info("saving to cache: " + f.getPath());
        byte[] bytes = DiskBackedFetchWrapper.SER.serialize(r);
        is.write(bytes);
      } catch (Exception e) {
        e.printStackTrace();
      }
    }

    return r;
  }

  @Override
  public void close() throws Exception {
    if (failOverResources != null)
      failOverResources.close();
  }

}
