CREATE TABLE nutrition_info (
	item_name 		VARCHAR(255) NOT NULL PRIMARY KEY,
	base_quantity 	DECIMAL(10, 2),
	calories		NUMERIC,
	protein			DECIMAL(10, 2),
	fibers			DECIMAL(10, 2),
	fats			DECIMAL(10, 2),
	carbs			DECIMAL(10, 2),
	created_at    	TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE nutrition_log (
	id 				BIGSERIAL PRIMARY KEY,
	user_id 		BIGINT REFERENCES users(id),
	item_name 		VARCHAR(255),
	quantity_grams	DECIMAL(10, 2),
	logged_at    	TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_nutrition_log_logged_at ON nutrition_log(logged_at);
CREATE INDEX idx_receipts_receipt_date ON receipts(receipt_date);
