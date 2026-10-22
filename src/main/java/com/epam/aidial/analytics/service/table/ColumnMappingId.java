package com.epam.aidial.analytics.service.table;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

/** Composite primary key of {@link ColumnMappingEntity}: {@code (tableName, sourceName)}. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ColumnMappingId implements Serializable {

    private String tableName;
    private String sourceName;
}
