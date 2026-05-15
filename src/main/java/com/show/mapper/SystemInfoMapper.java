package com.show.mapper;


import com.show.entity.DiskUsage;
import com.show.entity.SystemInfo;
import org.apache.ibatis.annotations.*;


import java.util.List;

@Mapper
public interface SystemInfoMapper {


    @Select("SELECT * FROM system_monitor ORDER BY created_at DESC")
    @Results({
            @Result(property = "id", column = "id"),
            @Result(property = "host", column = "host"),
            @Result(property = "port", column = "port"),
            @Result(property = "username", column = "username"),
            @Result(property = "password", column = "password"),
            @Result(property = "topInfo", column = "top_info"),
            @Result(property = "cpuUsage", column = "cpu_usage"),
            @Result(property = "freeInfo", column = "free_info"),
            @Result(property = "memoryUsage", column = "memory_usage"),
            @Result(property = "diskInfo", column = "disk_info"),
            @Result(property = "diskUsagePercent", column = "disk_usage_percent"),
            @Result(property = "createdAt", column = "created_at"),
            @Result(property = "diskUsages", column = "id", javaType = List.class,
                    many = @Many(select = "selectDiskUsagesByMonitorId"))
    })
    List<com.show.entity.SystemInfo> findAll();


    @Select("SELECT * FROM system_monitor where  id = #{id}")
    @Results({
            @Result(property = "id", column = "id"),
            @Result(property = "host", column = "host"),
            @Result(property = "port", column = "port"),
            @Result(property = "username", column = "username"),
            @Result(property = "password", column = "password"),
            @Result(property = "topInfo", column = "top_info"),
            @Result(property = "cpuUsage", column = "cpu_usage"),
            @Result(property = "freeInfo", column = "free_info"),
            @Result(property = "memoryUsage", column = "memory_usage"),
            @Result(property = "diskInfo", column = "disk_info"),
            @Result(property = "diskUsagePercent", column = "disk_usage_percent"),
            @Result(property = "createdAt", column = "created_at"),
            @Result(property = "diskUsages", column = "id", javaType = List.class,
                    many = @Many(select = "selectDiskUsagesByMonitorId"))
    })
    SystemInfo selectById(String id );
    
    @Select("SELECT * FROM system_monitor WHERE host = #{host}")
    @Results({
            @Result(property = "id", column = "id"),
            @Result(property = "host", column = "host"),
            @Result(property = "port", column = "port"),
            @Result(property = "username", column = "username"),
            @Result(property = "password", column = "password"),
            @Result(property = "topInfo", column = "top_info"),
            @Result(property = "cpuUsage", column = "cpu_usage"),
            @Result(property = "freeInfo", column = "free_info"),
            @Result(property = "memoryUsage", column = "memory_usage"),
            @Result(property = "diskInfo", column = "disk_info"),
            @Result(property = "diskUsagePercent", column = "disk_usage_percent"),
            @Result(property = "createdAt", column = "created_at"),
            @Result(property = "diskUsages", column = "id", javaType = List.class,
                    many = @Many(select = "selectDiskUsagesByMonitorId"))
    })
    SystemInfo selectByHost(String host);

    @Select("SELECT * FROM system_monitor WHERE host LIKE '%' || #{host} || '%'")
    @Results({
            @Result(property = "id", column = "id"),
            @Result(property = "host", column = "host"),
            @Result(property = "port", column = "port"),
            @Result(property = "username", column = "username"),
            @Result(property = "password", column = "password"),
            @Result(property = "topInfo", column = "top_info"),
            @Result(property = "cpuUsage", column = "cpu_usage"),
            @Result(property = "freeInfo", column = "free_info"),
            @Result(property = "memoryUsage", column = "memory_usage"),
            @Result(property = "diskInfo", column = "disk_info"),
            @Result(property = "diskUsagePercent", column = "disk_usage_percent"),
            @Result(property = "createdAt", column = "created_at"),
            @Result(property = "diskUsages", column = "id", javaType = List.class,
                    many = @Many(select = "selectDiskUsagesByMonitorId"))
    })
    List<SystemInfo> searchByHostLike(String host);

    @Select("SELECT * FROM disk_usage WHERE monitor_id = #{monitorId}")
    @Results({
            @Result(property = "id", column = "id"),
            @Result(property = "monitorId", column = "monitor_id"),
            @Result(property = "filesystem", column = "filesystem"),
            @Result(property = "type", column = "type"),
            @Result(property = "size", column = "size"),
            @Result(property = "used", column = "used"),
            @Result(property = "avail", column = "available"),
            @Result(property = "usagePercent", column = "usage_percent"),
            @Result(property = "mountedOn", column = "mounted_on"),
            @Result(property = "createdAt", column = "created_at")
    })
    List<DiskUsage> selectDiskUsagesByMonitorId(@Param("monitorId") Long monitorId);




    @Insert({
            "INSERT INTO system_monitor (",
            "  id, host, port, username, password, top_info, cpu_usage,",
            "  free_info, memory_usage, disk_info, disk_usage_percent, created_at",
            ") VALUES (",
            "  #{id}, #{host}, #{port}, #{username}, #{password}, #{topInfo}, #{cpuUsage},",
            "  #{freeInfo}, #{memoryUsage}, #{diskInfo}, #{diskUsagePercent}, #{createdAt}",
            ")"
    })
    @Options(useGeneratedKeys = true, keyProperty = "id")
    Integer insertSystemMonitor(SystemInfo monitor); // ✅ 类型匹配

    @Update({
            "<script>",
            "UPDATE system_monitor",
            "<set>",
            "  <if test='host != null and host != \"\"'>host = #{host},</if>",
            "  <if test='port != null'>port = #{port},</if>",
            "  <if test='username != null and username != \"\"'>username = #{username},</if>",
            "  <if test='password != null and password != \"\"'>password = #{password},</if>",
            "  <if test='topInfo != null and topInfo != \"\"'>top_info = #{topInfo},</if>",
            "  <if test='cpuUsage != null'>cpu_usage = #{cpuUsage},</if>",
            "  <if test='freeInfo != null and freeInfo != \"\"'>free_info = #{freeInfo},</if>",
            "  <if test='memoryUsage != null and memoryUsage != \"\"'>memory_usage = #{memoryUsage},</if>",
            "  <if test='diskInfo != null and diskInfo != \"\"'>disk_info = #{diskInfo},</if>",
            "  <if test='diskUsagePercent != null'>disk_usage_percent = #{diskUsagePercent},</if>",
            "  <if test='createdAt != null'>created_at = #{createdAt},</if>",
            "</set>",
            "WHERE id = #{id}",
            "</script>"
    })
    int updateSystemMonitorSelective(SystemInfo systemInfo);

    @Delete("DELETE FROM system_monitor WHERE id = #{id}")
    int deleteById(String id);
    
    @Select("SELECT * FROM disk_usage WHERE filesystem = #{host}")
    DiskUsage selectDiskUsagesByHost(String host);
}