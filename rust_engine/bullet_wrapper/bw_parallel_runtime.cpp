#include "bw_parallel_runtime.h"
#include "LinearMath/btThreads.h"
#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>
#include <stdexcept>

namespace {
thread_local bool insideTask = false;

class Scheduler final : public btITaskScheduler {
    struct Command {
        const btIParallelForBody* loop = nullptr;
        const btIParallelSumBody* sum = nullptr;
        std::atomic<int> next{0};
        std::atomic<bool> failed{false};
        int end = 0, grain = 1;
        btScalar results[BT_MAX_THREAD_COUNT]{};
    };
    std::vector<std::thread> workers;
    std::mutex mutex;
    std::condition_variable finished;
    std::unique_ptr<std::condition_variable[]> wake;
    Command* command = nullptr;
    unsigned generation = 0;
    int remaining = 0, participating = 0, registered = 0;
    bool stopping = false, indicesValid = true;
    uint64_t registeredIndices = 1;
    int maximum;
    std::atomic<int> width{1}, active{0}, peak{0}, jobs{0};

    void consume(Command& work, int ordinal) {
        bool previous = insideTask;
        insideTask = true;
        btScalar sum = 0;
        while (true) {
            int begin = work.next.fetch_add(work.grain, std::memory_order_relaxed);
            if (begin >= work.end) break;
            int count = active.fetch_add(1, std::memory_order_relaxed) + 1;
            int old = peak.load(std::memory_order_relaxed);
            while (old < count && !peak.compare_exchange_weak(old, count, std::memory_order_relaxed)) {}
            jobs.fetch_add(1, std::memory_order_relaxed);
#if defined(_MSC_VER) || defined(__cpp_exceptions) || defined(__EXCEPTIONS)
            try {
#endif
                if (work.loop) work.loop->forLoop(begin, std::min(begin + work.grain, work.end));
                else sum += work.sum->sumLoop(begin, std::min(begin + work.grain, work.end));
#if defined(_MSC_VER) || defined(__cpp_exceptions) || defined(__EXCEPTIONS)
            } catch (...) {
                work.failed.store(true);
                work.next.store(work.end);
            }
#endif
            active.fetch_sub(1, std::memory_order_relaxed);
        }
        work.results[ordinal] = sum;
        insideTask = previous;
    }

    void worker(int ordinal) {
        unsigned index = btGetCurrentThreadIndex();
        {
            std::lock_guard<std::mutex> lock(mutex);
            // 固定线程集合注册完毕后再允许任何物理任务进入。
            if (index == 0 || index >= BT_MAX_THREAD_COUNT || (registeredIndices & (uint64_t(1) << index))) indicesValid = false;
            else registeredIndices |= uint64_t(1) << index;
            ++registered;
            finished.notify_all();
        }
        unsigned seen = 0;
        std::unique_lock<std::mutex> lock(mutex);
        while (true) {
            wake[ordinal].wait(lock, [&] { return stopping || generation != seen; });
            if (stopping) return;
            seen = generation;
            if (ordinal > participating) continue;
            Command* work = command;
            lock.unlock();
            consume(*work, ordinal);
            lock.lock();
            if (--remaining == 0) finished.notify_all();
        }
    }

    btScalar dispatch(int begin, int end, int grain, const btIParallelForBody* loop, const btIParallelSumBody* sum) {
        if (begin >= end) return 0;
        if (insideTask || width.load() < 2 || end - begin <= grain) {
            if (loop) { loop->forLoop(begin, end); return 0; }
            return sum->sumLoop(begin, end);
        }
        Command work;
        work.loop = loop; work.sum = sum;
        work.next.store(begin); work.end = end; work.grain = std::max(1, grain);
        int participants = std::min(width.load(), (end - begin + work.grain - 1) / work.grain);
        {
            std::lock_guard<std::mutex> lock(mutex);
            command = &work;
            participating = participants - 1;
            remaining = participating;
            ++generation;
        }
        // 只唤醒当前分块需要的稳定 worker，避免小任务唤醒整台机器。
        for (int i = 1; i < participants; ++i) wake[i].notify_one();
        consume(work, 0);
        {
            std::unique_lock<std::mutex> lock(mutex);
            finished.wait(lock, [&] { return remaining == 0; });
            command = nullptr;
        }
        btScalar result = 0;
        for (int i = 0; i < participants; ++i) result += work.results[i];
#if defined(_MSC_VER) || defined(__cpp_exceptions) || defined(__EXCEPTIONS)
        if (work.failed.load()) throw std::runtime_error("Bullet MT worker failed after evaluation started");
#endif
        return result;
    }

public:
    explicit Scheduler(int count) : btITaskScheduler("MMD fixed workers"),
        wake(new std::condition_variable[count]), maximum(count) {}
    void start() {
        for (int i = 1; i < maximum; ++i) workers.emplace_back([this, i] { worker(i); });
        std::unique_lock<std::mutex> lock(mutex);
        finished.wait(lock, [&] { return registered == maximum - 1; });
    }
    ~Scheduler() override {
        { std::lock_guard<std::mutex> lock(mutex); stopping = true; }
        for (int i = 1; i < maximum; ++i) wake[i].notify_all();
        for (auto& thread : workers) if (thread.joinable()) thread.join();
    }
    bool valid() const { return indicesValid; }
    int getMaxNumThreads() const override { return maximum; }
    int getNumThreads() const override { return width.load(); }
    void setNumThreads(int count) override { width.store(std::max(1, std::min(count, maximum))); }
    void resetStats() { peak.store(0); jobs.store(0); }
    int peakActive() const { return peak.load(); }
    int jobCount() const { return jobs.load(); }
    void parallelFor(int begin, int end, int grain, const btIParallelForBody& body) override {
        dispatch(begin, end, grain, &body, nullptr);
    }
    btScalar parallelSum(int begin, int end, int grain, const btIParallelSumBody& body) override {
        return dispatch(begin, end, grain, nullptr, &body);
    }
};

class Runtime {
    std::mutex submission, mutex;
    std::condition_variable changed, finished;
    std::unique_ptr<Scheduler> scheduler;
    std::function<void()> task;
    bool ready = false, pending = false, stopping = false, valid = false, taskFailed = false;
    std::thread coordinator;

    void loop() {
        int count = std::max(1, std::min(64, int(std::thread::hardware_concurrency()) - 1));
        // 初始化失败时不再派发，旧顺序物理仍可运行。
#if defined(_MSC_VER) || defined(__cpp_exceptions) || defined(__EXCEPTIONS)
        try {
#endif
            if (btGetCurrentThreadIndex() == 0 && btGetTaskScheduler() == nullptr) {
                scheduler.reset(new Scheduler(count));
                btSetTaskScheduler(scheduler.get());
                scheduler->start();
                valid = scheduler->valid() && count > 1;
            }
#if defined(_MSC_VER) || defined(__cpp_exceptions) || defined(__EXCEPTIONS)
        } catch (...) { valid = false; }
#endif
        std::unique_lock<std::mutex> lock(mutex);
        ready = true;
        finished.notify_all();
        while (true) {
            changed.wait(lock, [&] { return stopping || pending; });
            if (stopping) break;
            auto work = std::move(task);
            lock.unlock();
            bool failed = false;
#if defined(_MSC_VER) || defined(__cpp_exceptions) || defined(__EXCEPTIONS)
            try { work(); } catch (...) { failed = true; }
#else
            work();
#endif
            lock.lock();
            taskFailed = failed;
            pending = false;
            finished.notify_all();
        }
        lock.unlock();
        if (scheduler) btSetTaskScheduler(nullptr);
        scheduler.reset();
    }

public:
    Runtime() : coordinator([this] { loop(); }) {
        std::unique_lock<std::mutex> lock(mutex);
        finished.wait(lock, [&] { return ready; });
    }
    ~Runtime() {
        { std::lock_guard<std::mutex> lock(mutex); stopping = true; }
        changed.notify_all();
        coordinator.join();
    }
    bool available() const { return valid; }
    int maxThreads() const { return valid ? scheduler->getMaxNumThreads() : 1; }
    int width() const { return valid ? scheduler->getNumThreads() : 1; }
    int peak() const { return valid ? scheduler->peakActive() : 1; }
    int jobs() const { return valid ? scheduler->jobCount() : 0; }
    bool run(int width, const std::function<void()>& work) {
        std::lock_guard<std::mutex> serialize(submission);
        std::unique_lock<std::mutex> lock(mutex);
        task = [&, width] {
            scheduler->setNumThreads(width);
            scheduler->resetStats();
            work();
        };
        pending = true;
        changed.notify_one();
        finished.wait(lock, [&] { return !pending; });
        return !taskFailed;
    }
};

Runtime* runtime() {
    static std::unique_ptr<Runtime> value = [] {
#if defined(_MSC_VER) || defined(__cpp_exceptions) || defined(__EXCEPTIONS)
        try { return std::unique_ptr<Runtime>(new Runtime()); }
        catch (...) { return std::unique_ptr<Runtime>(); }
#else
        return std::unique_ptr<Runtime>(new Runtime());
#endif
    }();
    return value.get();
}
}

bool bw_mt_available() { auto* r = runtime(); return r && r->available(); }
int bw_mt_max_threads() { auto* r = runtime(); return r ? r->maxThreads() : 1; }
bool bw_mt_run(int width, const std::function<void()>& task) { auto* r = runtime(); return r && r->run(width, task); }
int bw_mt_width() { auto* r = runtime(); return r ? r->width() : 1; }
int bw_mt_peak() { auto* r = runtime(); return r ? r->peak() : 1; }
int bw_mt_jobs() { auto* r = runtime(); return r ? r->jobs() : 0; }
