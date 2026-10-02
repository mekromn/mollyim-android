// Host-harness shim only: retains fatal assertions, no logging dependency.
#pragma once
#include <cstdlib>
struct DfHostCheck {explicit DfHostCheck(bool ok){if(!ok)std::abort();}template<class T>DfHostCheck& operator<<(const T&){return *this;}};
#define RTC_CHECK(x) DfHostCheck(static_cast<bool>(x))
#define RTC_CHECK_EQ(a,b) RTC_CHECK((a)==(b))
#define RTC_CHECK_NE(a,b) RTC_CHECK((a)!=(b))
#define RTC_CHECK_GT(a,b) RTC_CHECK((a)>(b))
#define RTC_CHECK_GE(a,b) RTC_CHECK((a)>=(b))
#define RTC_CHECK_LT(a,b) RTC_CHECK((a)<(b))
#define RTC_CHECK_LE(a,b) RTC_CHECK((a)<=(b))
#define RTC_DCHECK RTC_CHECK
#define RTC_DCHECK_EQ RTC_CHECK_EQ
#define RTC_DCHECK_NE RTC_CHECK_NE
#define RTC_DCHECK_GT RTC_CHECK_GT
#define RTC_DCHECK_GE RTC_CHECK_GE
#define RTC_DCHECK_LT RTC_CHECK_LT
#define RTC_DCHECK_LE RTC_CHECK_LE
#define RTC_DCHECK_NOTREACHED() RTC_CHECK(false)
