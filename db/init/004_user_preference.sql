CREATE TABLE user_preference (
	id 						BIGSERIAL PRIMARY KEY,
	daily_calorie_target 	DOUBLE PRECISION,
	daily_protein_target 	DOUBLE PRECISION,
	daily_fiber_target 	 	DOUBLE PRECISION,
	weekly_budget 			NUMERIC
);

INSERT INTO user_preference (id, daily_calorie_target, daily_protein_target, daily_fiber_target, weekly_budget)
VALUES (1, 1600, 120, 30, 50);
