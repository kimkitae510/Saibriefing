package com.threeam.story.repository;

import com.threeam.story.entity.StoryGoal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryGoalRepository extends JpaRepository<StoryGoal, Long> {

    List<StoryGoal> findByStoryIdOrderByIdAsc(Long storyId);

    long countByStoryId(Long storyId);
}
