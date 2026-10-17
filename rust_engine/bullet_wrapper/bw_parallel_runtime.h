#pragma once
#include <functional>

// 只有固定协调线程及其稳定 worker 可以进入 Bullet MT。
bool bw_mt_available();
int bw_mt_max_threads();
bool bw_mt_run(int width, const std::function<void()>& task);
int bw_mt_width();
int bw_mt_peak();
int bw_mt_jobs();
