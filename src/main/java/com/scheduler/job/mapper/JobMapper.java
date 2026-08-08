package com.scheduler.job.mapper;

import com.scheduler.job.dto.request.CreateJobRequest;
import com.scheduler.job.dto.response.CreateJobResponse;
import com.scheduler.job.dto.response.JobResponse;
import com.scheduler.job.dto.response.JobSummaryResponse;
import com.scheduler.job.entity.JobEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

/**
 * MapStruct mapper for converting between JobEntity and API DTO records.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface JobMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "claimedBy", ignore = true)
    @Mapping(target = "retryCount", ignore = true)
    @Mapping(target = "maxRetries", ignore = true)
    @Mapping(target = "lastError", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    JobEntity toEntity(CreateJobRequest request);

    CreateJobResponse toCreateJobResponse(JobEntity entity);

    JobResponse toJobResponse(JobEntity entity);

    JobSummaryResponse toJobSummaryResponse(JobEntity entity);
}
