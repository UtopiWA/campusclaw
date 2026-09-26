package com.campusclaw.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "assistants")
public class Assistant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(nullable = false)
    private String name;

    protected Assistant() {
    }

    public Assistant(Long classId, String name) {
        this.classId = classId;
        this.name = name;
    }

    public Long getId() { return id; }
    public Long getClassId() { return classId; }
    public String getName() { return name; }
}

