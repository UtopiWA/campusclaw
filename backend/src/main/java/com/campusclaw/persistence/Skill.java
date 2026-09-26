package com.campusclaw.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "skills")
public class Skill {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "assistant_id", nullable = false)
    private Long assistantId;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(nullable = false)
    private String name;

    protected Skill() {
    }

    public Skill(Long assistantId, Long classId, String name) {
        this.assistantId = assistantId;
        this.classId = classId;
        this.name = name;
    }

    public Long getId() { return id; }
    public Long getAssistantId() { return assistantId; }
    public Long getClassId() { return classId; }
    public String getName() { return name; }
}

