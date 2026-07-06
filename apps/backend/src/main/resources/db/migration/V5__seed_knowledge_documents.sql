-- Earthquake
INSERT INTO knowledge.documents (id, title, source_agency, source_uri, source_type, disaster_types, published_date, version, status, checksum) VALUES
  ('a0000000-0000-0000-0000-000000000001', 'Earthquake Safety Guidance', 'FEMA', 'https://www.fema.gov/earthquakes', 'PDF', ARRAY['EARTHQUAKE'], '2023-01-01', 1, 'ACTIVE', 'seed-eq-1');
INSERT INTO knowledge.chunks (id, document_id, content, chunk_index, section_heading, checksum) VALUES
  ('b0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', 'Drop, Cover, and Hold On: drop to the ground, take cover under a sturdy table or desk, and hold on until the shaking stops.', 0, 'During an Earthquake', 'seed-eq-c1'),
  ('b0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000001', 'If you are indoors, stay there. Do not run outside during shaking, as falling debris near buildings is a leading cause of injury.', 1, 'During an Earthquake', 'seed-eq-c2'),
  ('b0000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000001', 'After the shaking stops, check yourself and others for injuries. Expect aftershocks and be ready to drop, cover, and hold on again.', 2, 'After an Earthquake', 'seed-eq-c3'),
  ('b0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000001', 'Check gas, water, and electric lines for damage. If you smell gas, shut off the main valve, open windows, and leave immediately.', 3, 'After an Earthquake', 'seed-eq-c4');

-- Flood
INSERT INTO knowledge.documents (id, title, source_agency, source_uri, source_type, disaster_types, published_date, version, status, checksum) VALUES
  ('a0000000-0000-0000-0000-000000000002', 'Flood Safety and Water Purification', 'American Red Cross', 'https://www.redcross.org/floods', 'PDF', ARRAY['FLOOD'], '2023-01-01', 1, 'ACTIVE', 'seed-fl-1');
INSERT INTO knowledge.chunks (id, document_id, content, chunk_index, section_heading, checksum) VALUES
  ('b0000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000002', 'Never drive or walk into flood waters. Just six inches of moving water can knock over an adult, and one foot can sweep away a vehicle.', 0, 'Flood Safety', 'seed-fl-c1'),
  ('b0000000-0000-0000-0000-000000000006', 'a0000000-0000-0000-0000-000000000002', 'If tap water is contaminated after a flood, boil it at a rolling boil for one full minute to make it safe to drink.', 1, 'Water Purification', 'seed-fl-c2'),
  ('b0000000-0000-0000-0000-000000000007', 'a0000000-0000-0000-0000-000000000002', 'If you cannot boil water, disinfect it using unscented household liquid bleach: add 1/8 teaspoon per gallon of clear water and let stand 30 minutes.', 2, 'Water Purification', 'seed-fl-c3'),
  ('b0000000-0000-0000-0000-000000000008', 'a0000000-0000-0000-0000-000000000002', 'Boiled or chemically treated flood water that is cloudy should be filtered through clean cloth or allowed to settle before treatment.', 3, 'Water Purification', 'seed-fl-c4');

-- Hurricane
INSERT INTO knowledge.documents (id, title, source_agency, source_uri, source_type, disaster_types, published_date, version, status, checksum) VALUES
  ('a0000000-0000-0000-0000-000000000003', 'Hurricane Preparedness', 'FEMA', 'https://www.fema.gov/hurricanes', 'PDF', ARRAY['HURRICANE'], '2023-01-01', 1, 'ACTIVE', 'seed-hu-1');
INSERT INTO knowledge.chunks (id, document_id, content, chunk_index, section_heading, checksum) VALUES
  ('b0000000-0000-0000-0000-000000000009', 'a0000000-0000-0000-0000-000000000003', 'Before hurricane season, know your evacuation zone and route, and keep a disaster supply kit with at least three days of food and water.', 0, 'Preparedness', 'seed-hu-c1'),
  ('b0000000-0000-0000-0000-000000000010', 'a0000000-0000-0000-0000-000000000003', 'If a hurricane warning is issued for your area, evacuate immediately if told to do so by local officials.', 1, 'Evacuation', 'seed-hu-c2'),
  ('b0000000-0000-0000-0000-000000000011', 'a0000000-0000-0000-0000-000000000003', 'Board up windows, secure outdoor furniture, and turn your refrigerator to the coldest setting in case power is lost.', 2, 'Preparedness', 'seed-hu-c3'),
  ('b0000000-0000-0000-0000-000000000012', 'a0000000-0000-0000-0000-000000000003', 'After the storm, avoid downed power lines and standing water, which may be electrically charged.', 3, 'After a Hurricane', 'seed-hu-c4');

-- Tornado
INSERT INTO knowledge.documents (id, title, source_agency, source_uri, source_type, disaster_types, published_date, version, status, checksum) VALUES
  ('a0000000-0000-0000-0000-000000000004', 'Tornado Safety', 'American Red Cross', 'https://www.redcross.org/tornadoes', 'PDF', ARRAY['TORNADO'], '2023-01-01', 1, 'ACTIVE', 'seed-to-1');
INSERT INTO knowledge.chunks (id, document_id, content, chunk_index, section_heading, checksum) VALUES
  ('b0000000-0000-0000-0000-000000000013', 'a0000000-0000-0000-0000-000000000004', 'Go to a basement or an interior room on the lowest floor, away from windows, corners, and doors.', 0, 'During a Tornado', 'seed-to-c1'),
  ('b0000000-0000-0000-0000-000000000014', 'a0000000-0000-0000-0000-000000000004', 'If you are in a vehicle or mobile home, get out and go to a sturdy building or lie flat in a low, protected area.', 1, 'During a Tornado', 'seed-to-c2'),
  ('b0000000-0000-0000-0000-000000000015', 'a0000000-0000-0000-0000-000000000004', 'Cover your head and neck with your arms, and use a mattress, blanket, or helmet for extra protection if available.', 2, 'During a Tornado', 'seed-to-c3'),
  ('b0000000-0000-0000-0000-000000000016', 'a0000000-0000-0000-0000-000000000004', 'A tornado watch means conditions are favorable for a tornado; a tornado warning means one has been spotted and you must take shelter now.', 3, 'Watch vs Warning', 'seed-to-c4');

-- Tsunami
INSERT INTO knowledge.documents (id, title, source_agency, source_uri, source_type, disaster_types, published_date, version, status, checksum) VALUES
  ('a0000000-0000-0000-0000-000000000005', 'Tsunami Safety', 'FEMA', 'https://www.fema.gov/tsunamis', 'PDF', ARRAY['TSUNAMI'], '2023-01-01', 1, 'ACTIVE', 'seed-ts-1');
INSERT INTO knowledge.chunks (id, document_id, content, chunk_index, section_heading, checksum) VALUES
  ('b0000000-0000-0000-0000-000000000017', 'a0000000-0000-0000-0000-000000000005', 'If you feel a strong earthquake near the coast, move immediately to higher ground; do not wait for an official tsunami warning.', 0, 'Tsunami Warning Signs', 'seed-ts-c1'),
  ('b0000000-0000-0000-0000-000000000018', 'a0000000-0000-0000-0000-000000000005', 'A sudden, unusual recession of ocean water exposing the sea floor is a natural warning sign of an approaching tsunami.', 1, 'Tsunami Warning Signs', 'seed-ts-c2'),
  ('b0000000-0000-0000-0000-000000000019', 'a0000000-0000-0000-0000-000000000005', 'Move inland and to higher ground as quickly as possible; a tsunami can arrive within minutes of a nearby earthquake.', 2, 'During a Tsunami', 'seed-ts-c3'),
  ('b0000000-0000-0000-0000-000000000020', 'a0000000-0000-0000-0000-000000000005', 'Do not return to coastal areas until local officials say it is safe; tsunamis often arrive in multiple waves over several hours.', 3, 'After a Tsunami', 'seed-ts-c4');

-- Wildfire
INSERT INTO knowledge.documents (id, title, source_agency, source_uri, source_type, disaster_types, published_date, version, status, checksum) VALUES
  ('a0000000-0000-0000-0000-000000000006', 'Wildfire Preparedness and Evacuation', 'American Red Cross', 'https://www.redcross.org/wildfires', 'PDF', ARRAY['WILDFIRE'], '2023-01-01', 1, 'ACTIVE', 'seed-wf-1');
INSERT INTO knowledge.chunks (id, document_id, content, chunk_index, section_heading, checksum) VALUES
  ('b0000000-0000-0000-0000-000000000021', 'a0000000-0000-0000-0000-000000000006', 'If told to evacuate, leave immediately. Wear long sleeves and pants, and keep car windows and vents closed to keep smoke and embers out.', 0, 'Evacuation', 'seed-wf-c1'),
  ('b0000000-0000-0000-0000-000000000022', 'a0000000-0000-0000-0000-000000000006', 'Before wildfire season, clear dry leaves, brush, and flammable materials from within 30 feet of your home.', 1, 'Preparedness', 'seed-wf-c2'),
  ('b0000000-0000-0000-0000-000000000023', 'a0000000-0000-0000-0000-000000000006', 'If trapped by wildfire, call 911 and give your location, and look for a body of water, cleared area, or structure to shelter in.', 2, 'During a Wildfire', 'seed-wf-c3'),
  ('b0000000-0000-0000-0000-000000000024', 'a0000000-0000-0000-0000-000000000006', 'Wear an N95 mask if smoke is heavy, and keep windows and doors closed to limit smoke exposure indoors.', 3, 'Smoke Safety', 'seed-wf-c4');

-- Extreme Heat
INSERT INTO knowledge.documents (id, title, source_agency, source_uri, source_type, disaster_types, published_date, version, status, checksum) VALUES
  ('a0000000-0000-0000-0000-000000000007', 'Extreme Heat Safety', 'FEMA', 'https://www.fema.gov/extreme-heat', 'PDF', ARRAY['EXTREME_HEAT'], '2023-01-01', 1, 'ACTIVE', 'seed-eh-1');
INSERT INTO knowledge.chunks (id, document_id, content, chunk_index, section_heading, checksum) VALUES
  ('b0000000-0000-0000-0000-000000000025', 'a0000000-0000-0000-0000-000000000007', 'Drink plenty of water even if you do not feel thirsty, and avoid alcohol and caffeine, which can worsen dehydration.', 0, 'Heat Safety', 'seed-eh-c1'),
  ('b0000000-0000-0000-0000-000000000026', 'a0000000-0000-0000-0000-000000000007', 'Signs of heat stroke include a body temperature above 103F, hot red skin, and confusion; call 911 immediately if you suspect heat stroke.', 1, 'Heat Illness', 'seed-eh-c2'),
  ('b0000000-0000-0000-0000-000000000027', 'a0000000-0000-0000-0000-000000000007', 'Never leave children or pets in a parked vehicle; interior temperatures can rise to dangerous levels within minutes.', 2, 'Heat Safety', 'seed-eh-c3'),
  ('b0000000-0000-0000-0000-000000000028', 'a0000000-0000-0000-0000-000000000007', 'Check on elderly neighbors and relatives during heat waves, as older adults are at higher risk of heat-related illness.', 3, 'Vulnerable Populations', 'seed-eh-c4');

-- Winter Storm
INSERT INTO knowledge.documents (id, title, source_agency, source_uri, source_type, disaster_types, published_date, version, status, checksum) VALUES
  ('a0000000-0000-0000-0000-000000000008', 'Winter Storm Safety', 'American Red Cross', 'https://www.redcross.org/winter-storms', 'PDF', ARRAY['WINTER_STORM'], '2023-01-01', 1, 'ACTIVE', 'seed-ws-1');
INSERT INTO knowledge.chunks (id, document_id, content, chunk_index, section_heading, checksum) VALUES
  ('b0000000-0000-0000-0000-000000000029', 'a0000000-0000-0000-0000-000000000008', 'Never use a generator, grill, or camp stove indoors; they produce carbon monoxide, which can be fatal in enclosed spaces.', 0, 'Power Outage Safety', 'seed-ws-c1'),
  ('b0000000-0000-0000-0000-000000000030', 'a0000000-0000-0000-0000-000000000008', 'Insulin that has been exposed to freezing temperatures should not be used; store it between 36F and 46F, and use a cooler with ice packs if power is out.', 1, 'Medication Storage', 'seed-ws-c2'),
  ('b0000000-0000-0000-0000-000000000031', 'a0000000-0000-0000-0000-000000000008', 'Dress in layers and keep dry; wet clothing loses insulating value and increases the risk of hypothermia.', 2, 'Cold Weather Safety', 'seed-ws-c3'),
  ('b0000000-0000-0000-0000-000000000032', 'a0000000-0000-0000-0000-000000000008', 'Watch for signs of frostbite: numbness, white or grayish-yellow skin, and firm or waxy skin, especially on fingers, toes, ears, and nose.', 3, 'Frostbite', 'seed-ws-c4');
