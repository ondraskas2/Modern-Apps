#ifndef SCRATCHPAD_H
#define SCRATCHPAD_H

#include <cstdint>
#include <vector>
#include <cstring>
#include <cstdlib>
#include <unordered_map>
#include <android/log.h>

#define LOG_TAG "OfflineRouterNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/**
 * @brief Two-Level Page Table Scratchpad.
 */
class RoutingScratchpad {
public:
    struct Entry {
        uint32_t node_id;
        uint32_t g_fwd;
        uint32_t g_bwd;
        uint32_t p_fwd;
        uint32_t p_bwd;
        uint32_t last_name_off;
        uint8_t last_type;
    };

private:
    static constexpr uint32_t PAGE_BITS = 14;
    static constexpr uint32_t ROUTING_PAGE_SIZE = (1 << PAGE_BITS);
    static constexpr uint32_t ROUTING_PAGE_MASK = (ROUTING_PAGE_SIZE - 1);
    static constexpr uint32_t DIR_SIZE = (1ULL << 32) >> PAGE_BITS;

    Entry** m_directory;
    std::vector<uint32_t> m_active_pages;

public:
    RoutingScratchpad() {
        m_directory = (Entry**)calloc(DIR_SIZE, sizeof(Entry*));
        m_active_pages.reserve(1024);
    }

    ~RoutingScratchpad() {
        cleanup_pages();
        if (m_directory) free(m_directory);
    }

    void cleanup_pages() {
        for (uint32_t page_idx : m_active_pages) {
            if (m_directory[page_idx]) {
                free(m_directory[page_idx]);
                m_directory[page_idx] = nullptr;
            }
        }
        m_active_pages.clear();
    }

    inline void reset() {
        cleanup_pages();
    }

    inline Entry& get_entry(uint32_t node_id, int state = 0) {
        uint32_t index = (node_id << 1) | (state & 1);
        uint32_t dir_idx = index >> PAGE_BITS;
        uint32_t page_offset = index & ROUTING_PAGE_MASK;

        if (__builtin_expect(m_directory[dir_idx] == nullptr, 0)) {
            Entry* new_page = (Entry*)malloc(ROUTING_PAGE_SIZE * sizeof(Entry));
            memset(new_page, 0xFF, ROUTING_PAGE_SIZE * sizeof(Entry));
            for (uint32_t i = 0; i < ROUTING_PAGE_SIZE; i++) {
                new_page[i].last_type = 0;
                new_page[i].last_name_off = 0xFFFFFFFF;
            }
            m_directory[dir_idx] = new_page;
            m_active_pages.push_back(dir_idx);
        }

        Entry& e = m_directory[dir_idx][page_offset];
        if (__builtin_expect(e.node_id == 0xFFFFFFFF, 0)) {
            e.node_id = node_id;
        }
        return e;
    }

    inline Entry& operator[](uint32_t node_id) {
        return get_entry(node_id);
    }
};

/**
 * @brief Sparse traffic-speed table keyed by 64-bit global edge ID.
 *
 * Edge indices are now global u64 and can exceed the 32-bit space, so the old
 * flat 32-bit page directory can no longer address them. Traffic only covers a
 * sparse subset of edges, so a hash map is both correct and memory-efficient.
 */
class TrafficPageTable {
private:
    std::unordered_map<uint64_t, uint8_t> m_speeds;

public:
    TrafficPageTable() = default;

    inline void set_speed(uint64_t edge_id, uint8_t speed_kph) {
        m_speeds[edge_id] = speed_kph;
    }

    inline uint8_t get_speed(uint64_t edge_id) const {
        auto it = m_speeds.find(edge_id);
        return it == m_speeds.end() ? 0 : it->second;
    }

    void clear() {
        m_speeds.clear();
    }
};

#endif
