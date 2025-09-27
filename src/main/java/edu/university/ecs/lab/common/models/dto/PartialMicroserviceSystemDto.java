package edu.university.ecs.lab.common.models.dto;

import edu.university.ecs.lab.common.models.ir.Microservice;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PartialMicroserviceSystemDto {
    private Set<Microservice> microservices;
}
