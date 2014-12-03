package edu.jhu.hlt.fnparse.util;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Saves and loads Map<String, T> using Data(In|Out)putStreams
 * 
 * @author travis
 */
public class CacheSerUtil {

  public static <T> void save(
      File f,
      Map<String, T> cache,
      BiConsumer<T, DataOutputStream> serFunc) {
    try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(f))) {
      dos.writeInt(cache.size());
      for (Map.Entry<String, T> x : cache.entrySet()) {
        dos.writeUTF(x.getKey());
        serFunc.accept(x.getValue(), dos);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static <T> void load(
      File f,
      Map<String, T> cache,
      Function<DataInputStream, T> deserFunc) {
    try (DataInputStream dis = new DataInputStream(new FileInputStream(f))) {
      int n = dis.readInt();
      for (int i = 0; i < n; i++) {
        String key = dis.readUTF();
        T value = deserFunc.apply(dis);
        cache.put(key, value);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}