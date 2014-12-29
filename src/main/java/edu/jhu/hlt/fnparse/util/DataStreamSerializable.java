package edu.jhu.hlt.fnparse.util;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public interface DataStreamSerializable {

  public void serialize(DataOutputStream dos) throws IOException;

  public void deserialize(DataInputStream dis) throws IOException;
}
