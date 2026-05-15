package com.show.mapper;


import com.show.entity.DiskUsage;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;

@Mapper
public interface DiskUsageMapper {

        @Insert("INSERT INTO disk_usage (" +
                "monitor_id, filesystem, type, mounted_on, size, used, available, usage_percent, created_at" +
                ") VALUES (" +
                "#{monitorId}, #{filesystem}, #{type}, #{mountedOn}, #{size}, #{used}, #{avail}, #{usagePercent}, #{createdAt}" +
                ")")
        @Options(useGeneratedKeys = true, keyProperty = "id")
        void insert(DiskUsage diskUsage);


        @Delete("delete from disk_usage  where monitor_id = #{id}")
        Integer deleteById(String id);


}