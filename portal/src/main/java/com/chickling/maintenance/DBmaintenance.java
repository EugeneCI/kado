package com.chickling.maintenance;

import com.chickling.models.job.PrestoContent;
import com.chickling.sqlite.ConnectionManager;
import com.chickling.boot.Init;

import com.chickling.util.PrestoUtil;
import com.chickling.util.TimeUtil;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.apache.logging.log4j.core.config.Configuration;


import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.*;

import java.time.*;
import java.util.ArrayList;

/**
 * Created by jw6v on 2016/1/11.
 */
public class DBmaintenance {
    /**
     * 1. Modifying status of job and adding the stop time of the job which interrupted caused by system crash.
     * 2. Add the stop time of unfinished schedule.
     * 3. Add a default job if it is necessary.
     **/



    public Logger log = LogManager.getLogger(DBmaintenance.class);
    public DBmaintenance(){}

    public void maintain(){
        jobMaintain();
        jobHistoryMaintain();
        scheduleMaintain();
        ScheMgr smr=new ScheMgr();
        smr.startSche();
    }

    public void jobMaintain(){
        String CheckQueryUIJob="INSERT OR IGNORE INTO `main`.`Job` (`JobID`,`JobName`,`JobLevel`) VALUES ( 0,\"QueryUI\",1 )";
        PreparedStatement stat = null;
        ResultSet rs = null;
        try {
            stat = ConnectionManager.getInstance().getConnection().prepareStatement(CheckQueryUIJob);
            stat.execute();
            stat.close();
        }
        catch(SQLException e){
            log.error("Default Job Insert Failed cause: "+e.toString());
        }
    }

    public void jobHistoryMaintain(){
        String JobMaintain="UPDATE `main`.`Job_History` SET `JobStopTime`=CASE WHEN `JobStopTime` IS NULL or `JobStopTime`='' THEN ? ELSE `JobStopTime` END, `JobStatus`= CASE WHEN `JobStatus`=0 THEN 2 WHEN `JobStatus` IS NULL THEN 2 ELSE `JobStatus` END ";
        PreparedStatement stat = null;
        ResultSet rs = null;
        try {
            stat = ConnectionManager.getInstance().getConnection().prepareStatement(JobMaintain);
            stat.setString(1, TimeUtil.getCurrentTime());
            stat.execute();
            stat.close();
        }
        catch(SQLException e){
            log.error("Job maintenance is failed cause: "+e.toString());
        }
    }

    public void scheduleMaintain(){
        String ScheduleMaintain="UPDATE `main`.`Schedule_History` SET `ScheduleStopTime`= ? WHERE `ScheduleStopTime` IS NULL";
        PreparedStatement stat = null;
        ResultSet rs = null;
        try {
            stat = ConnectionManager.getInstance().getConnection().prepareStatement(ScheduleMaintain);
            stat.setString(1, TimeUtil.getCurrentTime());
            stat.execute();
            stat.close();
        }
        catch(SQLException e){
            log.error("Schedule maintenance is failed cause: "+e.toString());
        }
    }

    public void  deleteTempTableOverTTL(){
        String deleteTempTableSql="select JLID,JobOutput from Job_Log where Valid=0";
        PreparedStatement stat = null;
        ArrayList<Integer > JLID=new ArrayList<>();
        ArrayList<String>  tableList=new ArrayList<>();

        try {
            Connection conn=ConnectionManager.getInstance().getConnection();
            stat = conn.prepareStatement(deleteTempTableSql);
            ResultSet rs=stat.executeQuery();
            while (rs.next()){
                JLID.add(rs.getInt("JLID"));
                String[] output=rs.getString("JobOutput").split("/");
                tableList.add(output[output.length-1]);
            }
            rs.close();
            stat.close();

            log.info("Start Drop Temp Table and Update Record");

            PrestoUtil prestoutil=new PrestoUtil();
            for (int i = 0; i < JLID.size() ; i++) {
                String droptable="DROP TABLE if EXISTS "+Init.getDatabase()+"."+tableList.get(i);
                prestoutil.doJdbcRequest(droptable);
                String JobResultMaintain="UPDATE `main`.`Job_Log` SET `Valid`= 2 WHERE JLID="+JLID.get(i);
                stat= conn.prepareStatement(JobResultMaintain);
                stat.execute();
                stat.close();

            }
        }
        catch(SQLException e){
            log.error("Job Log maintenance is failed cause: "+e.toString());
        }
    }


//    public void deleteTempHDFSCSVdaily(){
//        log.info("====== Start Daily Delete HDFS Temp CSV file ====== ");
//        String csvHDFSpath=Init.getCsvtmphdfsPath()+"/csv";
//        FSFile fsFile=FSFile.newInstance(FSFile.FSType.HDFS);
//        List<String> files=new ArrayList<>();
//        try {
//            files.addAll(fsFile.listChildFileNames(csvHDFSpath));
//            for (String file:files){
//                String path=csvHDFSpath+"/"+file;
//                log.info("Delete File path is : "+path);
//                fsFile.deleteFile(path);
//            }
//            log.info("====== Delete HDFS Temp CSV Files Finish , Delete  files is [ "+files.size() +" ] ====== ");
//        } catch (IOException e) {
//            log.error("Delete HDFS Temp CSV Files Error : "+ExceptionUtils.getStackTrace(e));
//        }
//    }


    public void deleteLocalTempFileOverTTL(){
        log.info("====== Start Delete  Local Temp  file ====== ");
        String csvTTL= PrestoContent.CSV_TTL;
        String csvDirPath=Init.getCsvlocalPath();
        log.info("Temp File  Dir  : " +csvDirPath);
        File dir=null;
        try {
            int  deleteCount=0;
            ZonedDateTime znow=ZonedDateTime.now();
//            DateTime now=DateTime.now();
            dir = new File(csvDirPath);
            if (null!=dir.listFiles()){
                for (File localfiles: dir.listFiles()){
                    //delete temp json
                    if (localfiles.isDirectory() && Init.getTempDir().equalsIgnoreCase(localfiles.getName()) ){

                        for (File jsonDir:localfiles.listFiles()){
//                            String fileName = jsonDir.getName();
                            Path path = Paths.get(jsonDir.toURI());
                            BasicFileAttributes attr = Files.readAttributes(path, BasicFileAttributes.class);
                            if (attr.creationTime().toMillis() < znow.plusDays(Integer.parseInt(csvTTL)).toInstant().toEpochMilli()) {
                                //delete all json file from Temp JsonDir
                                for (File jsonfile:jsonDir.listFiles()){
                                    if (jsonfile.delete()) {
//                                        log.info("Delete json File is : [ " + jsonfile.getName() + " ] ");
                                        deleteCount++;
                                        if (0 == deleteCount % 50 && deleteCount > 0)
                                            log.info("Delete " + deleteCount + "  File !! ");
                                    }
                                }
                                //delete Json Dir
                                jsonDir.delete();
                            }
                        }
                    }else {
                        //delete temp csv
                        String fileName = localfiles.getName();
                        Path path = Paths.get(localfiles.toURI());
                        BasicFileAttributes attr = Files.readAttributes(path, BasicFileAttributes.class);
                        if (attr.creationTime().toMillis() < znow.plusDays(Integer.parseInt(csvTTL)).toInstant().toEpochMilli()) {
                            if (localfiles.delete()) {
                                log.info("Delete csv File is : [ " + fileName + " ] ");
                                deleteCount++;
                                if (0 == deleteCount % 50 && deleteCount > 0)
                                    log.info("Delete " + deleteCount + "  Files !! ");
                            }
                        }
                    }
                }
            }
            log.info("====== Delete Local Temp  Files Finish , Delete  files is [ "+deleteCount +" ] ====== ");
        } catch (Exception e) {
            log.error(ExceptionUtils.getStackTrace(e));
        }
    }

    public  void deleteLocalLogOverTTL(){
        log.info("====== Start Delete  Job & Schedule Log ====== ");
        String logTTL="-"+Init.getDeleteLogTTL();
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();
        RollingFileAppender app= (RollingFileAppender) config.getAppender("getLogDir");
        String logDirPath=app.getFileName().replaceFirst("[^\\/]+$", "");
        log.info("Local Log Dir  : " +logDirPath);
        File dir=null;
        try {
            int  deleteCount=0;

            ZonedDateTime znow=ZonedDateTime.now();
            dir = new File(logDirPath);
            if (null!=dir.listFiles()){
                for (File logfile: dir.listFiles()){
                    if (logfile.getName().contains("joblog") || logfile.getName().contains("ScheduleHistoryLog")){
                        Path path= Paths.get(logfile.toURI());
                        BasicFileAttributes attr= Files.readAttributes(path,BasicFileAttributes.class);
                        if (attr.creationTime().toMillis()< znow.plusDays(Integer.parseInt(logTTL)).toInstant().toEpochMilli()){
                            if (logfile.delete()){
                                deleteCount++;
                                if (0==deleteCount%10 && deleteCount>0)
                                    log.info("Delete "+deleteCount+" logs !! ");
                            }
                        }
                    }
                }
            }
            log.info("====== Delete Job & Schedule Log Finish , Delete  files is [ "+deleteCount +" ] ====== ");
        } catch (Exception e) {
            log.error(ExceptionUtils.getStackTrace(e));
        }
    }

    public void deleteSQLiteLogOverTTL(){

        log.info("====== Start Delete Log over TTL ====== ");
        String delete_Schedule_Job_History="DELETE FROM Schedule_Job_History where CRTIME< ?";
        String delete_Schedule_History="DELETE FROM Schedule_History where CRTIME< ?";
        String delete_Job_History="DELETE FROM Job_History where CRTIME<?";
        String delete_Job_Log="DELETE FROM Job_Log where CRTIME<?";
        String logTTL=Init.getDeleteLogTTL();

        delete_Schedule_Job_History=delete_Schedule_Job_History.replace("?","\'"+TimeUtil.beforeDate(logTTL)+"\'");
        delete_Schedule_History=delete_Schedule_History.replace("?","\'"+TimeUtil.beforeDate(logTTL)+"\'");
        delete_Job_History=delete_Job_History.replace("?","\'"+TimeUtil.beforeDate(logTTL)+"\'");
        delete_Job_Log=delete_Job_Log.replace("?","\'"+TimeUtil.beforeDate(logTTL)+"\'");


        Statement stat=null;
        try {
            stat=ConnectionManager.getInstance().getConnection().createStatement();
            stat.execute(delete_Schedule_Job_History);
            stat.execute(delete_Schedule_History);
            stat.execute(delete_Job_History);
            stat.execute(delete_Job_Log);
            stat.close();
        } catch (SQLException e) {
            log.error(ExceptionUtils.getStackTrace(e));
        }
        log.info("====== Finished Log Maintain ====== ");

    }


//    public  void backupSQLiteDB(){
//        String hdfsPath= YamlLoader.instance.getSqliteHDFSpath();
//        String sqliteDBpath=YamlLoader.instance.getSqliteLOCALpath();
//        log.info("====== Start Backup SQLite DB to HDFS ====== ");
//        log.info("SQLite HDFS Path is   : " +hdfsPath);
//
//        FSFile fsFile=FSFile.newInstance(FSFile.FSType.HDFS);
//        FsShell fsShell=new FsShell(fsFile.getFs().getConf());
//        try {
//            fsShell.run(new String[]{"-copyFromLocal","-f",sqliteDBpath,hdfsPath});
//            log.info("====== Backup SQLite DB  to HDFS Finish  ====== ");
//        } catch (Exception e) {
//            log.error("====== Backup SQLite DB  to HDFS ERROR !!! ====== ");
//            log.error(ExceptionUtils.getStackTrace(e));
//        }
//
//    }


    public void jobResultMaintain(){
        String JobResultMaintain="UPDATE `main`.`Job_Log` SET `Valid`= 0 WHERE `CRTIME`<?";
        PreparedStatement stat = null;
        String period= Init.getExpiration();
        log.info("TTL : "+period +" days  , delete day before "+TimeUtil.beforeDate(period));
        try {
            stat = ConnectionManager.getInstance().getConnection().prepareStatement(JobResultMaintain);
            stat.setString(1, TimeUtil.beforeDate(period));
            stat.execute();
            stat.close();
        }
        catch(SQLException e){
            log.error("Job Log maintenance is failed cause: "+e.toString());
        }
    }

    public static void main(String[] args) {
        DBmaintenance maintain=new DBmaintenance();
        Init.setCsvlocalPath("D:\\0_projects\\Kado\\logs");
        Init.setExpiration("1");


        maintain.deleteLocalTempFileOverTTL();



    }

}
