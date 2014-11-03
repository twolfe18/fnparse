package edu.jhu.hlt.fnparse.util;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.apache.log4j.Logger;

import redis.clients.jedis.Jedis;

public class RedisFileCache {
  public static final Logger LOG = Logger.getLogger(RedisFileCache.class);

  private String id;
  private String host = "localhost";
  private int port = 6379;
  private int db = 0;
  private transient Jedis r;

  public RedisFileCache(String id, String hostname, int port, int db) {
    assert id != null && id.length() > 0;
    this.id = id;
    getConnection();
  }

  private Jedis getConnection() {
    if (r == null) {
      LOG.info("[" + id + "] connecting to " + host + ":" + port + "@" + db);
      r = new Jedis(host, port);
      r.select(db);
    }
    return r;
  }

  public void close() {
    LOG.info("[" + id + "] closing connection to " + host + ":" + port + "@" + db);
    r.close();
    r = null;
  }

  public String toString() {
    return String.format("<RedisFileCache %s %s:%s db=%d>",
        id, host, port, db);
  }

  private byte[] getKey(String filename) {
    return (id + "." + filename).getBytes(StandardCharsets.UTF_8);
  }

  public boolean hasInpuStreamFor(String filename) {
    Jedis conn = getConnection();
    return conn.exists(getKey(filename));
  }

  public InputStream getInpuStreamFor(String filename) {
    LOG.info("[" + id + "] getting reader for " + filename);
    byte[] key = getKey(filename);
    Jedis conn = getConnection();
    byte[] result = conn.get(key);
    if (result == null)
      throw new RuntimeException("you need to populate this db with " + key);
    return new ByteArrayInputStream(result);
  }

  public void addFile(String filename, boolean overwrite) throws IOException {
    LOG.info("[" + id + "] adding " + filename);
    byte[] key = getKey(filename);
    byte[] contents = Files.readAllBytes(Paths.get(filename));
    Jedis conn = getConnection();
    if (overwrite)
      conn.set(key, contents);
    else
      conn.setnx(key, contents);
  }

  public static void main(String[] args) throws IOException {
    if (args.length != 4) {
      System.err.println("please provide:");
      System.err.println("1) an id for this redis-db");
      System.err.println("2) hostname");
      System.err.println("1) port");
      System.err.println("1) db index");
      return;
    }
    RedisFileCache rfc = new RedisFileCache(
        args[0], args[1], Integer.parseInt(args[2]), Integer.parseInt(args[3]));
    File td = new File("toydata");
    assert td.isDirectory();
    for (File f : td.listFiles())
      if (f.isFile() && f.getName().startsWith("fn15-"))
        rfc.addFile(f.getPath(), true);
    File ap = new File(td, "arg-pruning");
    assert ap.isDirectory();
    for (File f : ap.listFiles())
      if (f.isFile())
        rfc.addFile(f.getPath(), true);
    rfc.close();
  }
}
