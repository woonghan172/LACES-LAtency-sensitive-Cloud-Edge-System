# LAtency-sensitive Cloud Edge System (LACES)

## Abstract

Rising edge use cases and IoT applications increasingly depend on cloud based computation and storage for processing and retrieving latency-sensitive data. Nevertheless, inter-cloud and inter-region data access tends to suffer from substantial network latency and egress charges, while DRAM-only caching approaches are unaffordable and intractable to scale. Current solutions are mostly focused on optimizing intra-datacenter throughput, serverless execution latency, or cost-effective storage tiering, but lack a comprehensive device-edge-cloud layered solution specifically for latency-sensitive user device applications.

In this project, we will develop LACES, a multi-tier cloud management system aiming to minimize cloud-to-device latency while remaining cost-effective. LACES dynamically integrates inference request routing, service placement strategies using latency-sensitive scheduling, and multi-layer data storage management. By formally modeling tail-latency constraints and storage-tier considerations in placement, LACES will offer a principled solution to optimize data access paths for latency-sensitive applications.

We will start from implementing a basic KV store cloud system and expand the implementation to data storage management (data caching, data routing, etc.) and task scheduling (inference routing, job queue management, etc.) In our experiment, we would use S3 as our baseline, comparing with our implemented system, LACES.


## Motivation / concrete problem statement

Since the inception of the 'smartphone,' a diverse array of user devices—such as Meta Glass, Galaxy Ring, Apple Vision Pro, and even autonomous vehicles like Tesla—has emerged. These platforms hold immense potential, enabling unprecedented experiences including real-time language translation, gesture control in augmented reality, and autonomous driving. However, three critical factors must be considered regarding these devices: latency is paramount, they rely on a massive volume of AI applications (at least data-intensive applications), and they are increasingly expected to operate within cloud-based ecosystems (e.g., AWS’s IoT deployment platform, ‘Greengrass’).

When these devices execute intensive AI inference within cloud environments, achieving low latency becomes a significant challenge. First, user devices and edge cloud remain resource-constrained, while applications such as AI inferences  are computationally heavy. Second, cloud-based systems introduce inherent bottlenecks, specifically cold start delays and data offloading overhead. Consequently, this research aims to develop a low-latency cloud system optimized for the unique requirements of these next-generation user devices.

## Design
TBD

## Evaluation
TBD