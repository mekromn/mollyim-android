// Host-harness compatibility only, not a resampler substitute.
#pragma once
#include <algorithm>
#include <iterator>
namespace absl {template<class C,class T>void c_fill(C& c,const T& x){std::fill(std::begin(c),std::end(c),x);}}
