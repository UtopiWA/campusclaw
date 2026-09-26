package com.campusclaw.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "assignments")
public class Assignment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(nullable = false)
    private String title;

    protected Assignment() {
    }

    public Assignment(Long classId, String title) {
        this.classId = classId;
        this.title = title;
    }

    public Long getId() { return id; }
    public Long getClassId() { return classId; }
    public String getTitle() { return title; }
}

