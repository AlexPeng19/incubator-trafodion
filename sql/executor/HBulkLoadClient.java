// @@@ START COPYRIGHT @@@
//
// (C) Copyright 2014 Hewlett-Packard Development Company, L.P.
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
//
// @@@ END COPYRIGHT @@@

package org.trafodion.sql.HBaseAccess;
import java.net.URI;
import java.net.URISyntaxException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Iterator;
import java.io.File;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.PropertyConfigurator;
import org.apache.log4j.Logger;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.filter.SingleColumnValueFilter;
import org.apache.hadoop.hbase.filter.CompareFilter.CompareOp;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.security.access.AccessController;
import org.apache.hadoop.hbase.security.access.UserPermission;
import org.apache.hadoop.hbase.security.access.Permission;
import org.apache.hadoop.hbase.MasterNotRunningException;
import org.apache.hadoop.hbase.ZooKeeperConnectionException;
import org.apache.hadoop.hbase.snapshot.SnapshotCreationException;
import org.apache.hadoop.hbase.protobuf.generated.HBaseProtos.SnapshotDescription;
import org.apache.hadoop.hbase.protobuf.generated.HBaseProtos.SnapshotDescription.Type;
import org.apache.hadoop.hbase.snapshot.RestoreSnapshotException;
import org.apache.hadoop.hbase.io.hfile.CacheConfig;
import org.apache.hadoop.hbase.io.hfile.Compression;
import org.apache.hadoop.hbase.io.hfile.HFile;
//import org.apache.hadoop.hbase.io.compress.Compression.Algorithm;
import org.apache.hadoop.hbase.io.hfile.Compression.Algorithm;
import org.apache.hadoop.hbase.io.encoding.DataBlockEncoding;
import org.apache.hadoop.hbase.mapreduce.LoadIncrementalHFiles;
//import org.apache.hadoop.hbase.regionserver.BloomType; in v0.97
import org.apache.hadoop.hbase.regionserver.StoreFile.BloomType ;
//import org.apache.hadoop.hbase.client.Durability;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.trafodion.sql.HBaseAccess.StringArrayList;
import org.trafodion.sql.HBaseAccess.HTableClient;
//import org.trafodion.sql.HBaseAccess.HBaseClient;
import org.trafodion.sql.HBaseAccess.HTableClient.QualifiedColumn;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;

import java.nio.ByteBuffer;

public class HBulkLoadClient
{
  
  private final static FsPermission PERM_ALL_ACCESS = FsPermission.valueOf("-rwxrwxrwx");
  private final static FsPermission PERM_HIDDEN = FsPermission.valueOf("-rwx--x--x");
  private final static String BULKLOAD_STAGING_DIR = "hbase.bulkload.staging.dir";
  
  
  public static int BLOCKSIZE = 64*1024;
  public static String COMPRESSION = Compression.Algorithm.NONE.getName();
  String lastError;
  static Logger logger = Logger.getLogger(HBulkLoadClient.class.getName());
  Configuration config;
  HFile.Writer writer;

  HashMap< Integer, HTableClient.QualifiedColumn> qualifierMap = null;

  public HBulkLoadClient()
  {
    logger.debug("HBulkLoadClient.HBulkLoadClient() called.");

  }

  public HBulkLoadClient(Configuration conf) throws IOException
  {
    logger.debug("HBulkLoadClient.HBulkLoadClient(...) called.");
    if (conf == null)
    {
      throw new IOException("config not set");
    }
    config = HBaseConfiguration.create(conf);
  }

  public String getLastError() {
    return lastError;
  }

  void setLastError(String err) {
      lastError = err;
  }
  public boolean createHFile(String hFileLoc, String hfileName) throws IOException, URISyntaxException
  {
    logger.debug("HBulkLoadClient.createHFile() called.");
    
    FileSystem fs = FileSystem.get(config); 

    Path hfilePath = new Path(new Path(hFileLoc ), hfileName);
    hfilePath = hfilePath.makeQualified(hfilePath.toUri(), null);

    logger.debug("HBulkLoadClient.createHFile Path-- ");

    try
    {
    writer =    HFile.getWriterFactory(config, new CacheConfig(config))
                     .withPath(fs, hfilePath)
                     .withBlockSize(BLOCKSIZE)
                     .withCompression(COMPRESSION)
                     .withComparator(KeyValue.KEY_COMPARATOR)
                     .create();
    }
    catch (IOException e)
    {
        logger.debug("HBulkLoadClient.createHFile Exception" + e.getMessage());
        throw e;
    }
    return true;
  }
  public boolean addToHFile( RowsToInsert rows) throws IOException
  {
    logger.debug("Enter addToHFile() ");

    if (qualifierMap == null)
    {
      qualifierMap = new HashMap<Integer, HTableClient.QualifiedColumn>();

      RowsToInsert.RowInfo firstrow = rows.firstElement();
      HTableClient htc = new HTableClient();

      //for (RowsToInsert.ColToInsert col : firstrow.columns)
      for (int pos = 0 ; pos < firstrow.columns.size(); pos++)
      {
         HTableClient.QualifiedColumn qc =
                  htc.new QualifiedColumn(firstrow.columns.get(pos).qualName);
         qualifierMap.put(pos, qc);
      }
    }

    long now = System.currentTimeMillis();
    for (RowsToInsert.RowInfo row : rows)
    {
       byte[] rowId = row.rowId;

       //for (RowsToInsert.ColToInsert col : row.columns)
       for (int pos = 0 ; pos < row.columns.size(); pos++)
       {
          HTableClient.QualifiedColumn qc = qualifierMap.get(pos);
          //htc.new QualifiedColumn(col.qualName);
          KeyValue kv = new KeyValue(rowId,
                                     Arrays.copyOf(qc.getFamily(),qc.getFamily().length) ,
                                     Arrays.copyOf(qc.getName(),qc.getName().length),
                                     now,
                                     row.columns.get(pos).colValue);
          writer.append(kv);
       }
   }

    logger.debug("End addToHFile() ");
       return true;
  }
  public boolean closeHFile() throws IOException
  {
    String s = "not null";
    if (writer == null)
        s = "NULL";
    logger.debug("HBulkLoadClient.closeHFile() called." + s);

    writer.close();
    return true;
  }

  private boolean createSnapshot( String tableName, String snapshotName)
  throws MasterNotRunningException, IOException, SnapshotCreationException, InterruptedException
  {
    HBaseAdmin admin = null;
    try 
    {
      admin = new HBaseAdmin(config);
      List<SnapshotDescription>  lstSnaps = admin.listSnapshots();
      if (! lstSnaps.isEmpty())
      {
        for (SnapshotDescription snpd : lstSnaps) 
        {
            if (snpd.getName().compareTo(snapshotName) == 0)
            {
              logger.debug("HbulkLoadClient.createSnapshot() -- deleting: " + snapshotName + " : " + snpd.getName());
              admin.deleteSnapshot(snapshotName);
            }
        }
      }
      admin.snapshot(snapshotName, tableName);
   }
    catch (Exception e)
    {
      //log exeception and throw the exception again to teh parent
      logger.debug("HbulkLoadClient.createSnapshot() - Exception: " + e);
      throw e;
    }
    finally
    {
      //close HBaseAdmin instance 
      if (admin !=null)
        admin.close();
    }
    return true;
  }
  
  private boolean restoreSnapshot( String snapshotName, String tableName)
  throws IOException, RestoreSnapshotException
  {
    HBaseAdmin admin = null;
    try
    {
      admin = new HBaseAdmin(config);
      if (! admin.isTableDisabled(tableName))
          admin.disableTable(tableName);
      
      admin.restoreSnapshot(snapshotName);
  
      admin.enableTable(tableName);
    }
    catch (Exception e)
    {
      //log exeception and throw the exception again to the parent
      logger.debug("HbulkLoadClient.restoreSnapshot() - Exception: " + e);
      throw e;
    }
    finally
    {
      //close HBaseAdmin instance 
      if (admin != null) 
        admin.close();
    }

    return true;
  }
  private boolean deleteSnapshot( String snapshotName, String tableName)
      throws IOException
  {
    
    HBaseAdmin admin = null;
    boolean snapshotExists = false;
    try
    {
      admin = new HBaseAdmin(config);
      List<SnapshotDescription>  lstSnaps = admin.listSnapshots();
      if (! lstSnaps.isEmpty())
      {
        for (SnapshotDescription snpd : lstSnaps) 
        {
          //System.out.println("here 1: " + snapshotName + snpd.getName());
          if (snpd.getName().compareTo(snapshotName) == 0)
          {
            //System.out.println("deleting: " + snapshotName + " : " + snpd.getName());
            snapshotExists = true;
            break;
          }
        }
      }
      if (!snapshotExists)
        return true;
      if (admin.isTableDisabled(tableName))
          admin.enableTable(tableName);
      admin.deleteSnapshot(snapshotName);
    }
    catch (Exception e)
    {
      //log exeception and throw the exception again to the parent
      logger.debug("HbulkLoadClient.restoreSnapshot() - Exception: " + e);
      throw e;
    }
    finally 
    {
      //close HBaseAdmin instance 
      if (admin != null) 
        admin.close();
    }
    return true;
  }
  
  private void doSnapshotNBulkLoad(Path hFilePath, String tableName, HTable table, LoadIncrementalHFiles loader, boolean snapshot)
  throws MasterNotRunningException, IOException, SnapshotCreationException, InterruptedException, RestoreSnapshotException
  {
    HBaseAdmin admin = new HBaseAdmin(config);
    String snapshotName= null;
    if (snapshot)
    {
      snapshotName = tableName + "_SNAPSHOT";
      createSnapshot(tableName, snapshotName);
      logger.debug("HbulkLoadClient.doSnapshotNBulkLoad() - snapshot created: " + snapshotName);
    }
    try
    {
      logger.debug("HbulkLoadClient.doSnapshotNBulkLoad() - bulk load started ");
      loader.doBulkLoad(hFilePath, table);
      logger.debug("HbulkLoadClient.doSnapshotNBulkLoad() - bulk load is done ");
    }
    catch (IOException e)
    {
      logger.debug("HbulkLoadClient.doSnapshotNBulkLoad() - Exception: " + e.toString());
      if (snapshot)
      {
        restoreSnapshot(snapshotName, tableName);
        logger.debug("HbulkLoadClient.doSnapshotNBulkLoad() - snapshot restored: " + snapshotName);
        deleteSnapshot(snapshotName, tableName);
        logger.debug("HbulkLoadClient.doSnapshotNBulkLoad() - snapshot deleted: " + snapshotName);
        throw e;
      }
    }
    finally
    {
      if  (snapshot)
        deleteSnapshot(snapshotName, tableName);
      logger.debug("HbulkLoadClient.doSnapshotNBulkLoad() - snapshot deleted: " + snapshotName);
    }
    
  }
  public boolean doBulkLoad(String prepLocation, String tableName, boolean quasiSecure, boolean snapshot) throws Exception
  {
    logger.debug("HBulkLoadClient.doBulkLoad() - start");
    logger.debug("HBulkLoadClient.doBulkLoad() - Prep Location: " + prepLocation + 
                                             ", Table Name:" + tableName + 
                                             ", quasisecure : " + quasiSecure +
                                             ", snapshot: " + snapshot);

      
    HTable table = new HTable(config, tableName);
    LoadIncrementalHFiles loader = new LoadIncrementalHFiles(config);    
    Path prepPath = new Path(prepLocation );
    prepPath = prepPath.makeQualified(prepPath.toUri(), null);
    FileSystem prepFs = FileSystem.get(prepPath.toUri(),config);
    
    Path[] hFams = FileUtil.stat2Paths(prepFs.listStatus(prepPath));

    if (quasiSecure)
    {
      logger.debug("HbulkLoadClient.doBulkLoad() - quasi secure bulk load");
      //we need to add few lines of code to support secure hbase later 
      TrafBulkLoadClient client = new TrafBulkLoadClient(table) ;
  
      String hiddenToken = client.prepareBulkLoad(table.getTableDescriptor().getName());
      logger.debug("HBulkLoadClient.doBulkLoad() - hiddenToken: " + hiddenToken);
      
      Path hiddenPath = new Path(hiddenToken);
      hiddenPath = hiddenPath.makeQualified(hiddenPath.toUri(), null);
      FileSystem hiddenFs = FileSystem.get(hiddenPath.toUri(),config);
      
      if ( hiddenFs.getScheme().compareTo(prepFs.getScheme()) == 0 &&
          hiddenFs.getScheme().toUpperCase().compareTo(new String("HDFS")) == 0)
      {
        logger.debug("HBulkLoadClient.doBulkLoad() - moving hfiles from preparation directory to hidden directory");
        for (Path hfam : hFams) 
          prepFs.rename(hfam,hiddenPath);

        logger.debug("HBulkLoadClient.doBulkLoad() - adjusting hidden hfiles permissions");
        Path[] hiddenHFams = FileUtil.stat2Paths(hiddenFs.listStatus(hiddenPath));
        for (Path hiddenHfam : hiddenHFams) 
        {
           Path[] hiddenHfiles = FileUtil.stat2Paths(prepFs.listStatus(hiddenHfam));
           hiddenFs.setPermission(hiddenHfam,PERM_ALL_ACCESS );
           for (Path hiddehfile : hiddenHfiles)
           {
             logger.debug("HBulkLoadClient.doBulkLoad() - adjusting hidden hfile permissions:" + hiddehfile);
             hiddenFs.setPermission(hiddehfile,PERM_ALL_ACCESS);
           }
        }
        logger.debug("HBulkLoadClient.doBulkLoad() - quasi secure bulk load started ");
        doSnapshotNBulkLoad(hiddenPath,tableName,  table,  loader,  snapshot);
        logger.debug("HBulkLoadClient.doBulkLoad() - quasi secure bulk load is done ");
        client.cleanupBulkLoad(hiddenToken);
        logger.debug("HBulkLoadClient.doBulkLoad() - hidden directory cleanup done ");
      }
      else
        throw new Exception("HBulkLoadClient.doBulkLoad() - cannot perform load. source and target file systems are diffrent");
    }
    else
    {
      logger.debug("HBulkLoadClient.doBulkLoad() - adjusting hfiles permissions");
      for (Path hfam : hFams) 
      {
         Path[] hfiles = FileUtil.stat2Paths(prepFs.listStatus(hfam));
         prepFs.setPermission(hfam,PERM_ALL_ACCESS );
         for (Path hfile : hfiles)
         {
           logger.debug("HBulkLoadClient.doBulkLoad() - adjusting hfile permissions:" + hfile);
           prepFs.setPermission(hfile,PERM_ALL_ACCESS);
           
         }
      }
      logger.debug("HBulkLoadClient.doBulkLoad() - bulk load started. Loading directly from preparation directory");
      doSnapshotNBulkLoad(prepPath,tableName,  table,  loader,  snapshot);
      logger.debug("HBulkLoadClient.doBulkLoad() - bulk load is done ");
    }
    return true;
  }

  public boolean bulkLoadCleanup(String location) throws Exception
  {
      Path dir = new Path(location );
      dir = dir.makeQualified(dir.toUri(), null);
      FileSystem fs = FileSystem.get(dir.toUri(),config);
      fs.delete(dir, true);
      
      return true;

  }

}
