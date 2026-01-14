package com.example.thymeleaf;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit; // 必须有这行
public class Test {
    public static void main(String[] args) {
        ExecutorService executorService = Executors.newFixedThreadPool(3);

        System.out.println("主流程开始，准备分发异步任务...");

        // 2. 提交多个异步任务
        // 任务一：获取用户信息
        LatchUtils.submitTask(executorService, () -> {
            try {
                System.out.println("开始获取用户信息...");
                Thread.sleep(1000); // 模拟耗时
                System.out.println("获取用户信息成功！");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        // 任务二：获取订单信息
        LatchUtils.submitTask(executorService, () -> {
            try {
                System.out.println("开始获取订单信息...");
                Thread.sleep(1500); // 模拟耗时
                System.out.println("获取订单信息成功！");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // 任务三：获取商品信息
        LatchUtils.submitTask(executorService, () -> {
            try {
                System.out.println("开始获取商品信息...");
                Thread.sleep(500); // 模拟耗时
                System.out.println("获取商品信息成功！");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        System.out.println("所有异步任务已提交，主线程开始等待...");

        // 3. 等待所有任务完成，最长等待5秒
        boolean allTasksCompleted = LatchUtils.waitFor(5, TimeUnit.SECONDS);

        // 4. 根据等待结果继续主流程
        if (allTasksCompleted) {
            System.out.println("所有异步任务执行成功，主流程继续...");
        } else {
            System.err.println("有任务执行超时，主流程中断！");
        }

        // 5. 关闭线程池
        executorService.shutdown();

    }
}
