package com.pg.bigdata.utils.acl

import java.util.concurrent.Executors

import com.pg.bigdata.utils.fs.{FSOperationResult, _}
import com.pg.bigdata.utils.helpers.ConfigSerDeser
import com.pg.bigdata.utils.metastore._
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.fs.permission.{AclEntry, AclEntryScope, AclEntryType, FsAction}
import org.apache.spark.sql.functions.lit
import org.apache.spark.sql.{DataFrame, Dataset, SparkSession}

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

object AclManager extends Serializable {

  def modifyTableACLs(db: String, tableName: String, newPermission: FSPermission, partitionCount: Int = 30)
                     (implicit spark: SparkSession, confEx: Configuration): Array[FSOperationResult] = {
    import collection.JavaConverters._

    val sdConf = new ConfigSerDeser(confEx)
    val loc = getTableLocation(db, tableName)
    val files = getListOfTableFiles(db, tableName).toList

    getFileSystem(confEx, loc).modifyAclEntries(new Path(getRelativePath(loc)), Seq(AclManager.getAclEntry(newPermission.getDefaultLevelPerm())).asJava)

    println(files.head)
    println("Files to process: " + files.length)
    modifyACL(files, loc, partitionCount, sdConf, newPermission)

  }

  def modifyFolderACLs(folderUri: String, newPermission: FSPermission, partitionCount: Int = 30, driverParallelism: Int = 1000)(implicit spark: SparkSession, confEx: Configuration): Array[FSOperationResult] = {
    implicit val pool = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(driverParallelism))
    val sdConf = new ConfigSerDeser(confEx)
    val fs = getFileSystem(confEx, folderUri)
    val elements = listRecursively(fs, new Path(folderUri))
    val folders = elements.filter(_.isDirectory).map(_.path)
    val files = elements.filter(!_.isDirectory).map(_.path)

    println(files(0))
    println("Files to process: " + files.length)
    println("Folders to process: " + folders.length)

    println("Changing file and folders ACCESS ACLs: " + files.length)
    val resAccess = modifyACL(elements.map(_.path).toList, folderUri, partitionCount, sdConf, newPermission)
    println("Changing folders Default ACLs: " + folders.length)
    val resDefault = modifyACL(folders.toList, folderUri, partitionCount, sdConf, newPermission.getDefaultLevelPerm())
    resAccess.union(resDefault)
  }

  def modifyACL(paths: List[String], parentFolderURI: String, partitionCount: Int, sdConf: ConfigSerDeser, newFsPermission: FSPermission)
               (implicit spark: SparkSession): Array[FSOperationResult] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    spark.sparkContext.parallelize(paths, partitionCount).mapPartitions(part => {
      val conf = sdConf.get()
      val y = AclManager.getAclEntry(newFsPermission)
      val fs = getFileSystem(conf, parentFolderURI)
      val parentFolder = new Path(getRelativePath(parentFolderURI))
      if (fs.isDirectory(parentFolder))
        fs.modifyAclEntries(parentFolder, Seq(AclManager.getAclEntry(newFsPermission.getDefaultLevelPerm())).asJava)

      part.map(x => Future {
        Try({
          fs.modifyAclEntries(new Path(x), Seq(y).asJava)
          FSOperationResult(x, true)
        }).getOrElse(FSOperationResult(x, false))
      }).map(x => Await.result(x, 1.minute))
    }
    ).collect()
  }

  def getAclEntry(p: FSPermission): AclEntry = {
    val ptype = if (p.scope == p.USER) AclEntryType.USER
    else if (p.scope == p.GROUP) AclEntryType.GROUP
    else if (p.scope == p.OTHER) AclEntryType.OTHER
    else AclEntryType.MASK

    val perm = FsAction.getFsAction(p.permission)
    if (perm == null)
      throw new Exception("Provided permission " + p.permission + " is not valid for FsAction")
    val level = AclEntryScope.valueOf(p.level)
    val x = new AclEntry.Builder()
    val y = x.
      setType(ptype).
      setPermission(perm).
      setScope(level).
      setName(p.granteeObjectId).build()
    println(y.toString)
    y
  }

  case class FSPermission(scope: String, permission: String, level: String, granteeObjectId: String) {
    val USER: String = "user"
    val GROUP: String = "group"
    val OTHER: String = "other"
    val MASK: String = "mask"

    def getDefaultLevelPerm(): AclManager.FSPermission = FSPermission(scope, permission, "DEFAULT", granteeObjectId)
  }

  //def modifyACL()
}
