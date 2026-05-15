package com.show.mapper;


import com.show.entity.DiskUsage;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;

/**
 * 磁盘使用数据访问接口
 */
@Mapper
public interface DiskUsageMapper {

        /**
         * 插入磁盘使用记录
         */
        @Insert("INSERT INTO disk_usage (" +
                "monitor_id, filesystem, type, mounted_on, size, used, available, usage_percent, created_at" +
                ") VALUES (" +
                "#{monitorId}, #{filesystem}, #{type}, #{mountedOn}, #{size}, #{used}, #{avail}, #{usagePercent}, #{createdAt}" +
                ")")
        @Options(useGeneratedKeys = true, keyProperty = "id")
        void insert(DiskUsage diskUsage);


        /**
         * 根据监控记录 ID 删除对应的磁盘使用记录
         */
        @Delete("delete from disk_usage  where monitor_id = #{id}")
        Integer deleteById(String id);


}