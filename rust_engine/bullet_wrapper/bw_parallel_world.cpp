#include "bw_parallel_world.h"
#include "bw_parallel_runtime.h"
#include "BulletDynamics/Dynamics/btSimulationIslandManagerMt.h"
#include "BulletDynamics/Dynamics/btDiscreteDynamicsWorldMt.h"
#include "BulletDynamics/ConstraintSolver/btSequentialImpulseConstraintSolverMt.h"
#include "BulletDynamics/Dynamics/btRigidBody.h"
#include "LinearMath/btThreads.h"
#include <algorithm>

namespace {
class IslandManager final : public btSimulationIslandManagerMt {
public:
    int islands = 0, largestBodies = 0, largestConstraints = 0;
protected:
    void mergeIslands() override {
        islands = largestBodies = largestConstraints = 0;
        for (int i = 0; i < m_activeIslands.size(); ++i) {
            const Island& island = *m_activeIslands[i];
            if (island.isSleeping) continue;
            ++islands;
            largestBodies = std::max(largestBodies, island.bodyArray.size());
            largestConstraints = std::max(largestConstraints, island.constraintArray.size());
        }
        btSimulationIslandManagerMt::mergeIslands();
    }
};

struct PredictMotion : btIParallelForBody {
    btRigidBody** bodies;
    btScalar dt;
    void forLoop(int begin, int end) const override {
        for (int i = begin; i < end; ++i) {
            btRigidBody* body = bodies[i];
            if (!body->isStaticOrKinematicObject()) {
                body->applyDamping(dt);
                body->predictIntegratedTransform(dt, body->getInterpolationWorldTransform());
            }
        }
    }
};
}

BW_ParallelDispatcher::BW_ParallelDispatcher(btCollisionConfiguration* config)
    : btCollisionDispatcherMt(config) {
    // Bullet 使用全局线程索引；不能只按当前唤醒的宽度分配槽位。
    m_batchManifoldsPtr.resize(BT_MAX_THREAD_COUNT);
    m_batchReleasePtr.resize(BT_MAX_THREAD_COUNT);
}

void BW_ParallelDispatcher::dispatchAllCollisionPairs(
    btOverlappingPairCache* cache, const btDispatcherInfo& info, btDispatcher* dispatcher) {
    if (parallel) btCollisionDispatcherMt::dispatchAllCollisionPairs(cache, info, dispatcher);
    else btCollisionDispatcher::dispatchAllCollisionPairs(cache, info, dispatcher);
}

btCollisionDispatcher* bw_make_dispatcher(btCollisionConfiguration* config) {
    if (bw_mt_available()) return new BW_ParallelDispatcher(config);
    return new btCollisionDispatcher(config);
}

BW_ParallelWorld::BW_ParallelWorld(btDispatcher* dispatcher, btBroadphaseInterface* broadphase,
                                 btConstraintSolver* solver, btCollisionConfiguration* config)
    : btDiscreteDynamicsWorld(dispatcher, broadphase, solver, config) {
    // 两种执行方式共用同一个世界及刚体，切换时不重建模拟状态。
    m_islandManager->~btSimulationIslandManager();
    btAlignedFree(m_islandManager);
    m_islandManager = new (btAlignedAlloc(sizeof(IslandManager), 16)) IslandManager();
}

BW_ParallelWorld::~BW_ParallelWorld() {
    delete parallelSolver;
    delete pool;
}

bool BW_ParallelWorld::prepareParallel() {
    if (!bw_mt_available()) return false;
#if defined(_MSC_VER) || defined(__cpp_exceptions) || defined(__EXCEPTIONS)
    try {
#endif
        if (!pool) pool = new btConstraintSolverPoolMt(bw_mt_max_threads());
        if (!parallelSolver) parallelSolver = new btSequentialImpulseConstraintSolverMt();
        return true;
#if defined(_MSC_VER) || defined(__cpp_exceptions) || defined(__EXCEPTIONS)
    } catch (...) { return false; }
#endif
}

void BW_ParallelWorld::setParallel(bool enabled) {
    parallel = enabled;
    // dispatcher 的具体类型在创建时已由能力检查决定。
    if (bw_mt_available()) static_cast<BW_ParallelDispatcher*>(getDispatcher())->parallel = enabled;
}

bool BW_ParallelWorld::worthwhile() const {
    return m_nonStaticRigidBodies.size() >= 64 || m_constraints.size() >= 64;
}

void BW_ParallelWorld::solveConstraints(btContactSolverInfo& info) {
    if (!parallel) { btDiscreteDynamicsWorld::solveConstraints(info); return; }
    pool->prepareSolve(getNumCollisionObjects(), getDispatcher()->getNumManifolds());
    btSimulationIslandManagerMt::SolverParams params;
    params.m_solverPool = pool;
    params.m_solverMt = parallelSolver;
    params.m_solverInfo = &info;
    params.m_debugDrawer = getDebugDrawer();
    params.m_dispatcher = getDispatcher();
    auto* manager = static_cast<IslandManager*>(m_islandManager);
    manager->setMinimumSolverBatchSize(info.m_minimumSolverBatchSize);
    manager->buildAndProcessIslands(getDispatcher(), this, m_constraints, params);
    pool->allSolved(info, getDebugDrawer());
    stats.islands = manager->islands;
    stats.max_island_bodies = manager->largestBodies;
    stats.max_island_constraints = manager->largestConstraints;
}

void BW_ParallelWorld::predictUnconstraintMotion(btScalar dt) {
    if (!parallel || m_nonStaticRigidBodies.size() < 64) {
        btDiscreteDynamicsWorld::predictUnconstraintMotion(dt);
        return;
    }
    PredictMotion update;
    update.bodies = &m_nonStaticRigidBodies[0]; update.dt = dt;
    btParallelFor(0, m_nonStaticRigidBodies.size(), 50, update);
}

void BW_ParallelWorld::resetParallelStats() { stats = {}; stats.thread_width = 1; }
void BW_ParallelWorld::finishParallelStats() {
    stats.thread_width = bw_mt_width();
    stats.peak_active = bw_mt_peak();
    stats.jobs = bw_mt_jobs();
}
