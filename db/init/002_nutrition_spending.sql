CREATE TABLE nutrition_info (
	item_name 		VARCHAR(255) NOT NULL PRIMARY KEY,
	base_quantity 	DOUBLE PRECISION,
	calories		DOUBLE PRECISION,
	protein			DOUBLE PRECISION,
	fibers			DOUBLE PRECISION,
	fats			DOUBLE PRECISION,
	carbs			DOUBLE PRECISION,
	created_at    	TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE nutrition_log (
	id 				BIGSERIAL PRIMARY KEY,
	user_id 		BIGINT REFERENCES users(id),
	item_name 		VARCHAR(255),
	quantity_grams	DOUBLE PRECISION,
	logged_at    	TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_nutrition_log_logged_at ON nutrition_log(logged_at);
CREATE INDEX idx_receipts_receipt_date ON receipts(receipt_date);
