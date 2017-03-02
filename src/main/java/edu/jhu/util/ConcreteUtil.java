package edu.jhu.util;

import java.io.File;
import java.io.IOException;

import org.apache.thrift.TDeserializer;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TCompactProtocol;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.tutils.FileUtil;

public class ConcreteUtil {
  public static final TDeserializer deser = new TDeserializer(new TCompactProtocol.Factory());
  
  public static Communication readOneComm(File f) throws IOException, TException {
    byte[] bs = FileUtil.readBytes(f);
    Communication c = new Communication();
    deser.deserialize(c, bs);
    return c;
  }
  
  public static Communication readOneCommSafe(File f) {
    try {
      return readOneComm(f);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}