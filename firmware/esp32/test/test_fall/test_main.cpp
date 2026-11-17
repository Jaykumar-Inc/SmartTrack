#include <unity.h>
#include "FallDetector.h"
void normal_motion() {
  FallDetector d;
  for (uint32_t t = 0; t < 10000; t += 10) TEST_ASSERT_FALSE(d.update(t, 1, 0, 0));
  TEST_ASSERT_EQUAL(FallDetector::IDLE, d.state);
}
void fall_sequence() {
  FallDetector d;
  d.update(100, 0.2, 0, 0);
  d.update(200, 3.2, 0, 0);
  d.update(210, 1, 50, 0);
  TEST_ASSERT_TRUE(d.update(2300, 1, 50, 0));
  TEST_ASSERT_EQUAL(FallDetector::FALL, d.state);
  TEST_ASSERT_FALSE(d.update(2310, 1, 50, 0));
  d.update(32400, 1, 50, 0);
  TEST_ASSERT_EQUAL(FallDetector::IDLE, d.state);
}
void no_impact_times_out() {
  FallDetector d;
  d.update(100, 0.2, 0, 0);
  d.update(1500, 1, 60, 0);
  TEST_ASSERT_EQUAL(FallDetector::IDLE, d.state);
}
void rollover() {
  FallDetector d;
  d.update(0xfffffff0, 0.2, 0, 0);
  d.update(100, 3.2, 0, 0);
  TEST_ASSERT_EQUAL(FallDetector::IMPACT, d.state);
}
int main() {
  UNITY_BEGIN();
  RUN_TEST(normal_motion);
  RUN_TEST(fall_sequence);
  RUN_TEST(no_impact_times_out);
  RUN_TEST(rollover);
  return UNITY_END();
}
