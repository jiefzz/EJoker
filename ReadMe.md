# EJoker
java版ENode，思路、框架设计都来自汤雪华ENode(C#)，并在基础层使用了大量java自身的特性。
ENode是一个开源的应用开发框架，为开发人员提供了一套完整的基于DDD+CQRS+ES+(in-memory)+EDA架构风格的解决方案。

详见：https://github.com/tangxuehua/enode
或汤雪华博客:http://www.cnblogs.com/netfocus


Java 没有官方的协程方案也没有async/await语法糖，所以EJoker底层异步模型使用两个方案 `伪异步` 以及 `Quasar fiber`
	
伪异步方式由EJoker内部提供线程池，提交任务时检查当前线程是否为线程池中的线程，或者任务队列是否有排队任务，如果满足条件，则由提交者线程直接执行当前提交的任务。

获得`伪异步`版本
	
	mvn -Dmaven.test.skip=true clean compile package install

Quasar协程为第三方java协程方案，中文资料不太懂，慎用。

Quasar协程下写代码有一些细节需要注意 详细看`http://docs.paralleluniverse.co/quasar/#suspendable-libraries`

获得`Quasar fiber`版本

	mvn -Dmaven.test.skip=true clean compile package install org.fortasoft:gradle-maven-plugin:1.0.8:invoke package install
