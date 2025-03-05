package edu.university.ecs.lab.common.models.ir;

import com.github.javaparser.Range;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class Location {
    protected int startLine;
    protected int endLine;

    public Location(Range range) {
        if(range == null) {
            startLine = -1;
            endLine = -1;
        } else {
            startLine = range.begin.line;
            endLine = range.end.line;
        }
    }
}