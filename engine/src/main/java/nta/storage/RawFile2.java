package nta.storage;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.SortedSet;
import java.util.TreeSet;

import nta.catalog.Column;
import nta.catalog.Options;
import nta.catalog.Schema;
import nta.catalog.TableMeta;
import nta.catalog.statistics.Stat;
import nta.catalog.statistics.StatSet;
import nta.datum.Datum;
import nta.datum.DatumFactory;
import nta.engine.NConstants;
import nta.engine.TCommonProtos.StatType;
import nta.engine.ipc.protocolrecords.Fragment;
import nta.storage.exception.AlreadyExistsStorageException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

public class RawFile2 extends Storage {

  public static final Log LOG = LogFactory.getLog(RawFile2.class);

  public RawFile2(Configuration conf) {
    super(conf);
  }

  @Override
  public Appender getAppender(TableMeta meta, Path path)
  throws IOException {
    return new RawFileAppender(conf, meta, path, true);
  }

  @Override
  public Scanner openScanner(Schema schema, Fragment[] tablets)
  throws IOException {
    return new RawFileScanner(conf, schema, tablets);
  }

  private static final int SYNC_ESCAPE = -1;
  private static final int SYNC_HASH_SIZE = 16;
  private static final int SYNC_SIZE = 4 + SYNC_HASH_SIZE;
  public static int SYNC_INTERVAL;

  public static class RawFileScanner extends FileScanner {
    private FSDataInputStream in;
    private SortedSet<Fragment> tabletSet;
    private Iterator<Fragment> tableIter;
    private Fragment curTablet;
    private FileSystem fs;
    private byte[] sync;
    private byte[] checkSync;
    private long start, end;
    private long startPos, headerPos, lastSyncPos; 
    private long pageStart, pageLen;
    private long curTupleOffset;
    private Options option;
    
    private byte[] buf;
    private byte[] extra;
    private byte[] bufextra;
    private int bufSize, extraSize;
    private final static int DEFAULT_BUFFER_SIZE = 65536;
    
    private ByteArrayInputStream bin;
    private DataInputStream din;     

    public RawFileScanner(Configuration conf, final Schema schema, 
        final Fragment[] tablets) throws IOException {
      super(conf, schema, tablets);
      init();
    }

    public RawFileScanner(Configuration conf, final Schema schema, 
        final Fragment[] tablets, Options option) throws IOException {
      super(conf, schema, tablets);
      this.option = option;
      init();
    }

    private void init() throws IOException {
      // set default page size.
      this.bufSize = DEFAULT_BUFFER_SIZE;
      
      this.sync = new byte[SYNC_HASH_SIZE];
      this.checkSync = new byte[SYNC_HASH_SIZE];

      // set tablet iterator.
      this.tabletSet = new TreeSet<Fragment>();
      for (Fragment t: tablets) {
        this.tabletSet.add(t);
      }
      this.tableIter = tabletSet.iterator();
      openNextTablet();
    }

    private boolean openNextTablet() throws IOException {
      if (this.in != null) {
        this.in.close();
      }
      
      // set tablet information.
      if (tableIter.hasNext()) {
        curTablet = tableIter.next();
        this.fs = curTablet.getPath().getFileSystem(this.conf);
        this.in = fs.open(curTablet.getPath());
        this.start = curTablet.getStartOffset();
        if (curTablet.getLength() == -1) {
          long fileLength = fs.getFileStatus(curTablet.getPath()).getLen();
          this.end = curTablet.getStartOffset() + fileLength;
        } else {
          this.end = curTablet.getStartOffset() + curTablet.getLength();
        }
        
        readHeader();
        // set correct start offset.
        headerPos = in.getPos();
        if (start < headerPos) {
          in.seek(headerPos);
        } else {
          in.seek(start);
        }
        if (in.getPos() != headerPos) {
          in.seek(in.getPos()-SYNC_SIZE);
          while(in.getPos() < end) {
            if (checkSync()) {
              lastSyncPos = in.getPos();
              break;
            } else {
              in.seek(in.getPos()+1);
            }
          }
        }
        startPos = in.getPos();
        
        if (in.getPos() >= end)
          if (!openNextTablet())
            return false;
        
        pageBuffer();
        return true;
      } else {
        return false;
      }
    }

    private void readHeader() throws IOException {
      SYNC_INTERVAL = in.readInt();
      in.read(this.sync, 0, SYNC_HASH_SIZE);
      lastSyncPos = in.getPos();
    }

    private boolean checkSync() throws IOException {
      in.readInt();                           // escape
      in.read(checkSync, 0, SYNC_HASH_SIZE);  // sync
      if (!Arrays.equals(checkSync, sync)) {
        in.seek(in.getPos()-SYNC_SIZE);
        return false;
      } else {
        return true;
      }
    }
    
    private boolean checkSyncinPage() throws IOException {
      din.mark(1);
      din.readInt();                           // escape
      din.read(checkSync, 0, SYNC_HASH_SIZE);  // sync
      if (!Arrays.equals(checkSync, sync)) {
        din.reset();
        return false;
      } else {
        return true;
      }
    }
    
    private boolean pageBuffer() throws IOException {
      if (tabletable() < 1) {
        // initialize.
        this.curTupleOffset = 0;
        this.bufSize = DEFAULT_BUFFER_SIZE;
        return false;
      }
      
      // set buffer size.
      if (tabletable() <= bufSize) {
        bufSize = (int) tabletable();
      } else {
        bufSize = DEFAULT_BUFFER_SIZE;
      }
      
      buf = new byte[bufSize];
      pageStart = in.getPos();
      in.read(buf);
      
      checkSyncMarker();
      bufextra = new byte[bufSize + extraSize];
      System.arraycopy(buf, 0, bufextra, 0, bufSize);
      if (extraSize > 0)
        System.arraycopy(extra, 0, bufextra, bufSize, extraSize);
      
      pageLen = bufextra.length;
      bin = new ByteArrayInputStream(bufextra);
      din = new DataInputStream(bin);
      this.curTupleOffset = 0;
     
      return true;
    }
    
    private void checkSyncMarker() throws IOException {
      long mark = in.getPos();
      int i;
      in.seek(in.getPos()-SYNC_SIZE);
      for (i = 1; in.available() != 0; i++) {
        if (!checkSync())
          in.seek(in.getPos()+1);
        else break;
      }

      in.seek(mark);
      if (i > 1) {
        extra = new byte[i - 1];
        in.read(extra);
        extraSize = extra.length;
      } else extraSize = 0;
    }

    @Override
    public void seek(long offset) throws IOException {
    	if (offset >= this.pageStart + this.pageLen  ||
    			offset < this.pageStart) {
    		in.seek(offset);
    		bin.close();
    		din.close();
    		bin = new ByteArrayInputStream(new byte[0]);
    		din = new DataInputStream(bin);
    		pageBuffer();
    	} else {
    		long bufferOffset = offset - this.pageStart;
    		if(this.curTupleOffset == bufferOffset) {
    			
    		} else if( this.curTupleOffset < bufferOffset) {
    		  din.skip(bufferOffset - this.curTupleOffset);
    			this.curTupleOffset = bufferOffset;
    		} else {
    		  din.close();
    			bin.close();
    			bin = new ByteArrayInputStream(bufextra);
    			din = new DataInputStream(bin);
    			this.curTupleOffset = bufferOffset;
    			din.skip(this.curTupleOffset);
    		}
    	}
    }
    
    @Override
    public long getNextOffset() {
    	return this.pageStart + this.curTupleOffset;
    }
    
    @Override
    public long tabletable() throws IOException{
      return this.end - in.getPos();
    }
    
    @Override
    public Tuple next() throws IOException {
      if (din.available() < 1) {
        if (!pageBuffer()) 
          if (!openNextTablet())
            return null;
      }
      if (checkSyncinPage())
        this.curTupleOffset += SYNC_SIZE;
      if (din.available() < 1) {
        if (!pageBuffer()) 
          if (!openNextTablet())
            return null;
      }
      
      int i;
      VTuple tuple = new VTuple(schema.getColumnNum());

      boolean [] contains = new boolean[schema.getColumnNum()];
      for (i = 0; i < schema.getColumnNum(); i++) {
        contains[i] = din.readBoolean();
        this.curTupleOffset += DatumFactory.createBool(true).size();
      }
      Column col = null;
      for (i = 0; i < schema.getColumnNum(); i++) {
    	Datum datum;
        if (contains[i]) {
          col = schema.getColumn(i);
          switch (col.getDataType()) {
          case BYTE:
        	datum = DatumFactory.createByte(din.readByte());
        	this.curTupleOffset += datum.size();
            tuple.put(i, datum );
            break;
          case SHORT:
        	datum = DatumFactory.createShort(din.readShort());
        	this.curTupleOffset += datum.size();
            tuple.put(i, datum );
            break;
          case INT:
            datum = DatumFactory.createInt(din.readInt());
        	this.curTupleOffset += datum.size();
            tuple.put(i, datum );
            break;
          case LONG:
        	datum = DatumFactory.createLong(din.readLong());   
        	this.curTupleOffset += datum.size();  
            tuple.put(i, datum );
            break;
          case FLOAT:
        	datum = DatumFactory.createFloat(din.readFloat());  
        	this.curTupleOffset += datum.size();  
            tuple.put(i, datum);
            break;
          case DOUBLE:
        	datum = DatumFactory.createDouble(din.readDouble());
        	this.curTupleOffset += datum.size();
            tuple.put(i, datum);
            break;
          case STRING:
        	this.curTupleOffset += DatumFactory.createShort((short)0).size();  
            short len = din.readShort();
            byte[] buf = new byte[len];
            din.read(buf, 0, len);
            datum = DatumFactory.createString(new String(buf));
            this.curTupleOffset += datum.size();
            tuple.put(i, datum);
            break;
          case IPv4:
        	byte[] ipv4 = new byte[4];
            din.read(ipv4, 0, 4);
            datum = DatumFactory.createIPv4(ipv4);
            this.curTupleOffset += datum.size();
            tuple.put(i, datum);
            break;
          default:
            break;
          }
        }
      }
      return tuple;
    }

    @Override
    public void reset() throws IOException {
      init();
    }
		
    @Override
    public Schema getSchema() {     
      return this.schema;
    }

    @Override
    public void close() throws IOException {
      if (in != null) {
        in.close();
      }
    }   
  }

  public static class RawFileAppender extends FileAppender {
    private FSDataOutputStream out;
    private long lastSyncPos;
    private FileSystem fs;
    private byte[] sync;
    
    // statistics
    private final boolean statsEnabled;
    private StatSet statSet = null;
    private Stat numRowStat = null;
    private Stat outputBytesStat = null;

    public RawFileAppender(Configuration conf, final TableMeta meta, 
        final Path path, boolean statsEnabled) throws IOException {
      super(conf, meta, path);      

      fs = path.getFileSystem(conf);

      if (!fs.exists(path.getParent())) {
        throw new FileNotFoundException(path.toString());
      }

      if (fs.exists(path)) {
        throw new AlreadyExistsStorageException(path);
      }

      SYNC_INTERVAL = conf.getInt(NConstants.RAWFILE_SYNC_INTERVAL, SYNC_SIZE*100);     
      sync = new byte[SYNC_HASH_SIZE];
      lastSyncPos = 0;

      out = fs.create(path);

      MessageDigest md;
      try {
        md = MessageDigest.getInstance("MD5");
        md.update((path.toString()+System.currentTimeMillis()).getBytes());
        sync = md.digest();
      } catch (NoSuchAlgorithmException e) {
        LOG.error(e);
      }

      writeHeader();
      
      this.statsEnabled = statsEnabled;
      if (statsEnabled) {
        this.statSet = new StatSet();
        this.numRowStat = new Stat(StatType.TABLE_NUM_ROWS);        
        this.statSet.putStat(this.numRowStat);
        this.outputBytesStat = new Stat(StatType.TABLE_NUM_BYTES);
        this.statSet.putStat(this.outputBytesStat);
      }
		}
		
		private void writeHeader() throws IOException {
			out.writeInt(SYNC_INTERVAL);
			out.write(sync);
			out.flush();
			lastSyncPos = out.getPos();
		}
		
		@Override
		public void addTuple(Tuple t) throws IOException {
			checkAndWriteSync();
			Column col = null;
			for (int i = 0; i < schema.getColumnNum(); i++) {
				out.writeBoolean(t.contains(i));
			}
			for (int i = 0; i < schema.getColumnNum(); i++) {
				if (t.contains(i)) {
					col = schema.getColumn(i);
					switch (col.getDataType()) {
					case BYTE:
						out.writeByte(t.getByte(i).asByte());
						break;
					case STRING:
						byte[] buf = t.getString(i).asByteArray();
						if (buf.length > 256) {
							buf = new byte[256];
							byte[] str = t.getString(i).asByteArray();
							System.arraycopy(str, 0, buf, 0, 256);
						} 
						out.writeShort(buf.length);
						out.write(buf, 0, buf.length);
						break;
					case SHORT:
						out.writeShort(t.getShort(i).asShort());
						break;
					case INT:
						out.writeInt(t.getInt(i).asInt());
						break;
					case LONG:
						out.writeLong(t.getLong(i).asLong());
						break;
					case FLOAT:
						out.writeFloat(t.getFloat(i).asFloat());
						break;
					case DOUBLE:
						out.writeDouble(t.getDouble(i).asDouble());
						break;
					case IPv4:
						out.write(t.getIPv4Bytes(i));
						break;
					case IPv6:
						out.write(t.getIPv6Bytes(i));
						break;
					default:
						break;
					}
				}
			}
			
      // Statistical section
      if (statsEnabled) {
        numRowStat.increment();
      }
    }

		@Override
		public void flush() throws IOException {
			out.flush();
		}

		@Override
		public void close() throws IOException {
			if (out != null) {
			  if (statsEnabled) {
			    outputBytesStat.setValue(out.getPos());
			  }
				sync();
				out.flush();
				out.close();
			}
		}
		
		private void sync() throws IOException {
			if (lastSyncPos != out.getPos()) {
				out.writeInt(SYNC_ESCAPE);
				out.write(sync);
				lastSyncPos = out.getPos();
			}
		}
		
		synchronized void checkAndWriteSync() throws IOException {
			if (out.getPos() >= lastSyncPos + SYNC_INTERVAL) {
				sync();
			}
		}

    @Override
    public StatSet getStats() {
      return statSet;
    }
	}
}

